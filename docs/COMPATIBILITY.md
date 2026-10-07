# Compatibility

This public preview is an independent receiver, not an Apple-certified CarPlay accessory. The experimental bundled accessory identity is extractable and its future acceptance is not guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Android 9+ APK; wireless Wi-Fi Direct path needs Android 10+ |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Previous private builds: wired and wireless picture, touch and audio confirmed on the development car with iPhone XS / iOS 18.7.10 |
| Other cars | Mixed community reports across DiLink generations; not a certified model support list |
| Current release | DiLink5.1: HUD/street names and Car hotspot confirmed; Wi-Fi Direct improved, occasional audio cutouts remain |
| Wi-Fi | Prefer 5 GHz without an established station connection; align to a supported existing station channel; explicit 2.4 GHz fallback for firmware that rejects 5 GHz or automatic channel selection |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

## Geely Android 5.1 / API 22 port (Phase 3A)

Real Okavango validation is complete for Phase 3A: `ap0` owns `192.168.43.1/24`,
Manual Hotspot readiness passes, and cross-device mDNS/NSD over the hotspot passes.

This branch supports installation/launch and legacy media initialization on API 22. This is
**not** evidence of a working CarPlay session. Connections and vendor integrations remain disabled
by `LegacyLaunchBuild`; Bluetooth RFCOMM/iAP2, authentication, USB projection, Wi-Fi Direct and
LocalOnlyHotspot are not enabled by this phase.

The Okavango has confirmed installation/launch on `alps E01`, `mt6735`, Android 5.1/API 22,
firmware `SWVX11A0126H5173.00036`. The Phase 3A debug build was
`0.2.12-api22-phase3a-device-test`. Settings exposes read-only live interface/address,
prefix/route, station/AP evidence and stable Manual Hotspot readiness diagnostics.
**Start Phase 3A network test** performs readiness checks then a 20-second system NSD
registration/discovery test with `_diplay-phase3a._tcp.`. It does not advertise CarPlay,
load accessory credentials, accept socket traffic or send any handshake/control probe.
It retains a multicast lock only during the test and stops on app pause, timeout,
interface/address change or lost readiness. The diagnostic report includes the same
network/test data. `DiPlayPhase3ADevice` logs selection, readiness, NSD and failure outcomes.
On API 22, default connectivity is observable but Internet validation is unknown; no
external Internet probe is made. Missing firmware SSID/route information is reported,
not guessed. A self-discovered advertisement alone is not evidence of laptop reachability.

Existing-network managers use API 22-compatible service lookup. Manual hotspot sampling on
API 22 reads `activeNetworkInfo`, Wi-Fi `NetworkInfo`, `WifiInfo.ipAddress`, API 21
`Network`/`LinkProperties`, and local interfaces instead of `activeNetwork` (API 23).
Before/after legacy observations must agree. A missing default route is valid for a local AP;
a connected station whose interface cannot be identified remains unknown, not an eligible AP.
Three stable address/interface samples are still required. Existing Wi-Fi uses the already
connected station link; it never creates an AP, joins Wi-Fi, or changes the default route.
Before API 30, its callback request removes the legacy constructor's default capabilities
instead of calling the newer public `clearCapabilities()` method.
Station mode must use Existing Wi-Fi, not Manual Hotspot.

API 22 uses system NSD even when interface mDNS is requested: the legacy `MulticastSocket`
constructor conflicts with Android's mDNS daemon on UDP 5353. NSD registration/discovery/
resolution and TXT setters are available on API 22, and the Wi-Fi multicast lock is retained.
Android controls NSD's multicast/interface scope; `setHost` is not an interface-binding guarantee.
Android 5.1 may omit TXT attributes from resolved peers, so an endpoint can have no Bluetooth ID.
Phase 3A needs only its address/port, not pairing. API 23+ retains the existing JmDNS/NSD choices;
API 34+ multi-address resolution remains guarded. Discovery can be tested with
`probeControlService = false` to avoid sending the CarPlay connect request.

API 22 emulator checks cover enumeration, local same-port IPv4/scoped-IPv6 sockets, injected
AP readiness, and system NSD discovery/resolution. The emulator exposes Ethernet, not a real
Wi-Fi hotspot. Okavango firmware must still prove AP ownership, station/AP concurrency,
address changes, multicast delivery to another LAN device, and NSD operation with cellular/VPN
or multiple interfaces. Phase 3B was subsequently authorized for transport-only validation,
not authentication or full CarPlay.

## Geely Phase 3B.2 NForetek metadata/bind discovery

The supplied hardware report confirms `INfCommandSpp` methods:
`isSppConnected(String): boolean`, `isSppServiceReady(): boolean`,
`registerSppCallback(INfCallbackSpp): boolean`,
`reqSppConnectedDeviceAddressList(): void`,
`unregisterSppCallback(INfCallbackSpp): boolean`, plus prohibited connect/disconnect/send
methods. Callback metadata includes readiness, state changes, address/name arrays,
received bytes and Apple iAP authentication requests. No Apple-specific SPP command
was listed. Public signatures alone do not establish callback Stub transaction dispatch.
Phase 3B.3 live SPP binding remains disabled following the local APK audit below;
no callback implementation/registration or auto-create SPP binding is added.
The manual **Export vendor Bluetooth APKs** utility
resolves and copies both installed sourceDir APKs without invoking vendor code and
verifies sizes/SHA-256. No system path is hardcoded or modified.

Hardware Phase 3B.1 inventory identifies `com.nforetek.bt` / `GocsdkServer` and
`NfServiceBluetooth`, `NfServiceSpp`, HFP, A2DP, AVRCP and PBAP services, plus
`com.neusoft.geely.btphone.nf.BtManagerService` and wheeljack Settings Bluetooth services.
At the initial Phase 3B.2 implementation no vendor APK or live head unit was available.
The subsequent hardware report confirmed a zero-flag bind to `NfServiceBluetooth`,
descriptor `com.nforetek.bt.aidl.INfCommandBluetooth`, and clean unbind. Actual APK
inspection now establishes the implementations described below; a service's name and
export status alone are not evidence of working SPP/iAP2 support.

### Phase 3B.3 local bytecode audit (2026-10-06)

Both APKs were extracted on the real Okavango and inspected locally with Android SDK
`dexdump -d` and `aapt dump xmltree`. No APK/code was uploaded or embedded into DiPlay.
`/vendor-apks/` is explicitly ignored by Git; both supplied APKs are untracked.
This audit describes protocol facts and behavior, not a copied vendor implementation.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| Bluetooth-GocBtAPI.apk | 471097 | `aff5a4de709f95392e92158a348ad0c9a584d616d1e07203da307437ee009674` |
| btphoneNF.apk | 903362 | `91bf3712f2155bd6f16338619d8f2b82e8cadba25c0dbf3fa8f888927c31268b` |

The two APKs contain matching SPP interface signatures and Stub transaction constants.
`INfCommandSpp` and `INfCallbackSpp` extend `android.os.IInterface`. Each public abstract
Stub extends Binder, has a public no-argument constructor attaching its descriptor,
public `asBinder(): IBinder`, static `asInterface(IBinder): <interface>`, and public
`onTransact(int, Parcel, Parcel, int): boolean`. `asInterface` returns null for null
input, uses the descriptor's local interface when compatible, otherwise constructs
the vendor's nonpublic `Stub$Proxy`. Proxy methods implement the interface plus
`asBinder(): IBinder` and `getInterfaceDescriptor(): String`.

Verified command descriptor: `com.nforetek.bt.aidl.INfCommandSpp`.

| Transaction | Exact public interface method |
| ---: | --- |
| 1 | `boolean isSppServiceReady()` |
| 2 | `boolean registerSppCallback(INfCallbackSpp)` |
| 3 | `boolean unregisterSppCallback(INfCallbackSpp)` |
| 4 | `boolean reqSppConnect(String)` - prohibited |
| 5 | `boolean reqSppDisconnect(String)` - prohibited |
| 6 | `void reqSppConnectedDeviceAddressList()` |
| 7 | `boolean isSppConnected(String)` |
| 8 | `void reqSppSendData(String, byte[])` - prohibited |

Verified callback descriptor: `com.nforetek.bt.aidl.INfCallbackSpp`.

| Transaction | Exact public interface method |
| ---: | --- |
| 1 | `void onSppServiceReady()` |
| 2 | `void onSppStateChanged(String address, String name, int prevState, int newState)` |
| 3 | `void onSppErrorResponse(String, int)` |
| 4 | `void retSppConnectedDeviceAddressList(int totalNum, String[] addressList, String[] nameList)` |
| 5 | `void onSppDataReceived(String, byte[])` |
| 6 | `void onSppSendData(String, int)` |
| 7 | `void onSppAppleIapAuthenticationRequest(String)` - observation only, no response |

Both Proxies use synchronous transactions with flags=0 and a reply Parcel, not
one-way callbacks. Interface tokens are enforced; callback arguments are read in the
listed order. Registration passes the callback's strong Binder and the service
reconstructs it through the genuine callback `Stub.asInterface`. Replies carry an
exception marker; command booleans are encoded as integers. Void calls also reply.
The standard descriptor transaction is `INTERFACE_TRANSACTION` (1598968902).
These numbers are audit facts only: no handwritten transact or guessed dispatch was
added to application code. No `getSppState` or Apple/iAP command exists on this SPP
interface; state would be callback-driven if the service actually implemented it.

**Implementation blocker, beyond interface metadata:** in Bluetooth-GocBtAPI.apk,
`NfServiceSpp.onBind` returns `GocsdkService.getInstance().spp`, constructed as
`com.goodocom.gocsdkserver.CommandSppImp`, a genuine `INfCommandSpp$Stub` subclass.
All its boolean methods (readiness, registration, unregistration, connection predicate,
connect and disconnect) unconditionally return false. The list query and send method
immediately return without work. Registration does not retain the supplied callback
or dispatch readiness. This installed implementation cannot prove SPP transport or
provide a connected-device callback merely because complete AIDL classes are present.
DEX method offsets in that APK: readiness `0x0b81c4`, registration `0x0b81d8`,
list query `0x0b8200`, unregistration `0x0b823c`.

**Lifecycle blocker:** `NfServiceSpp.onDestroy` calls `GocsdkService.destroy`.
That method sets the shared singleton's `running` flag false and clears `INSTANCE`.
Creating a missing singleton starts `SerialThread`; this is not isolated SPP state.
Starting the normally stopped SPP service and later unbinding the last client can
therefore disturb the shared vehicle Bluetooth backend. Actual disruption was not
tested on the car. `NfServiceBluetooth` has the same destroy hook, so even zero-flag
binding is not a universal guarantee of side-effect-free vendor lifecycle behavior.
No service-start/stop workaround, hidden APIs, callback fake or inert Binder is added.
The operator explicitly chose to keep live SPP binding disabled and investigate
implemented read-only alternatives. Phase 3B.2 inspection still lists SPP metadata,
but the cache-status build excludes SPP from its optional bind selector as well.

### Implemented read-only alternative found

`NfServiceBluetooth.onBind` returns `GocsdkService.getInstance().bluetooth`, a
`CommandBluetoothImp` implementing the genuine `INfCommandBluetooth$Stub`.
Unlike SPP, the following audited methods have implementations that only log/read
cached fields (or return the indicated constant); they do not send controller commands:

| Exact method | Verified transaction | Behavior |
| --- | ---: | --- |
| `String getBtLocalName()` | 12 | Reads singleton `localName` |
| `String getBtLocalAddress()` | 14 | Reads singleton `localAddress` |
| `boolean isBtEnabled()` | 16 | Reads cached `currentEnable` |
| `int getBtState()` | 17 | Maps `currentEnable` to 302/on or 300/off |
| `String getNfServiceVersionName()` | 4 | Reads cached `versionData` |
| `boolean isBluetoothServiceReady()` | 1 | Always true; not proof of radio readiness |

The subsequent Phase 3B.3 cache-status milestone below authorizes a narrowly allowlisted
getter test using the installed vendor's real `Stub.asInterface`, not raw Binder numbers.
The later real-car cache-status test confirmed accessibility and agreement with
GEELY_BT, as recorded below. Cache freshness across OFF/ON transitions is not yet
established. No live getter calls were made as part of the static audit.
`reqBtPairedDevices()` is deliberately NOT a passive cache getter: its body clears
the cached list and invokes `GocsdkCommandSender.getPairList()`. Do not include it in
a cache-only test. No callback registration is needed merely to read the getters above.

The Geely wrapper is now confirmed, rather than inferred:
`BtManagerService` binds the NForetek Bluetooth service, resolves the genuine
`INfCommandBluetooth$Stub.asInterface`, and supplies it to `SettingServer`.
With no action (or an action other than `com.neusoft.geely.btphone.control`), its
Binder is `com.neusoft.geely.btphone.service.IBtPhoneManager`. `getBtSettings()` returns
`com.ecarx.xui.adaptapi.bt1.settings.IBtSettings`; the settings implementation delegates
name/address/state/version reads to the NForetek getters above. The control action
instead returns `IBtControl`, whose implemented method plays Bluetooth audio; it is
not an appropriate read-only investigation path. Critically, the phone manager's
`getSpp(): com.ecarx.xui.adaptapi.bt1.spp.ISpp` unconditionally returns null.
The manager manifest declares it enabled/exported without a service permission.
NForetek services have intent filters, enabled=true and no explicit export/permission
attributes; on this target-22 APK their filters make them exported by default.

The approved next milestone is cached vendor Bluetooth status, with lifecycle safeguards
and no auto-create, not an iPhone/SPP connection or authentication. The supplied APKs do
not establish any usable SPP/iAP transport.

### Phase 3B.3 cache-only status hardware build

Build `0.2.12-api22-phase3b3-nforetek-cache-status` adds the separate Settings section
**Phase 3B.3 cache-only vendor Bluetooth status** and operator button
**Read cached NForetek Bluetooth status**. No model, package lookup, class loader,
service bind or getter runs during launch/Settings construction. Existing Phase 3A
networking and Phase 3B safe-startup behavior are unchanged.

After the button press, service export/enabled/permission eligibility is rechecked,
then only the explicit NfServiceBluetooth component is bound with flags=0. A false bind
or timeout is a failure, not an invitation to start the service. The five approved
getters are invoked once each using public methods on the genuine command interface.
No callback is registered and no other vendor interface method is invoked.

The installed PackageManager sourceDir APK must match the SHA-256 audited above;
split APKs, unreadable files and different versions are rejected before vendor
asInterface/getter execution. DexClassLoader loads the installed APK using a private
optimization cache, without a native-library path. Public interface/Stub signatures,
IInterface ancestry, class-loader origin and Binder descriptor are validated.
The genuine static `INfCommandBluetooth.Stub.asInterface(IBinder)` is invoked;
no reconstructed AIDL, inert Binder or handwritten transact is used.

The visible/exported report contains bind return/connection, descriptor, resolved
interface/implementation, raw getter values and interpretations (302=ON, 300=OFF,
other=UNKNOWN), exceptions, completion/cancellation and unbind result. Null/empty names,
addresses or versions and zero addresses remain unavailable, not fabricated values.
Getter exceptions are isolated so the remaining approved cache getters can still report.
`DiPlayPhase3B3Device` logs the same outcomes; no credentials or payloads are logged.

Descriptor/class loading/getters run off the UI thread. A five-second deadline unbinds
and discards late results; pause/destroy only cleans an explicitly active test.
An in-flight IPC cannot be forcibly cancelled: no later getters are scheduled once
cancellation is observed, and a process-wide guard rejects additional tests until the
worker returns, including across Activity/model replacement. No startService,
stopService or auto-create is used. Vendor service destruction remains a hardware risk
despite zero-flag binding; keep the vehicle UI active and check Bluetooth after the test.
The older Phase 3B.2 optional component bind selector now explicitly disallows SPP;
use only the dedicated cache-status button for this milestone.

### Phase 3B.3 real Okavango result: PASS

The operator confirmed the cache-status build on the Geely E01 / Android 5.1:
flags=0 bind succeeded, descriptor was
`com.nforetek.bt.aidl.INfCommandBluetooth`, genuine installed Stub/Proxy resolution
succeeded, and clean unbind succeeded. With vehicle Bluetooth ON, the cache returned
`GEELY_BT`, address `00:0D:86:2F:20:23`, enabled `true`, raw state `302` / ON.
No service-version value was supplied in this hardware result.
Standard Android continued to expose the separate disabled `CAR_BT` adapter.
NForetek/Geely is therefore the confirmed vehicle Bluetooth implementation; this
does not establish any SPP, iAP2, authentication or CarPlay capability.

### Phase 3B.4 static transport-path audit

This is a static-only audit of the same two fingerprinted local APKs above.
Both complete DEX files, manifests, non-generated interface call sites, command
implementations, callback forwarding and serial receive dispatch were inspected.
Packaged ARM native-library JNI exports/imports and strings were inspected locally
with NDK LLVM tools. No vendor library was executed and no vehicle command was sent.
Runtime code, build identity, transport gates and startup behavior are unchanged.
The extracted APKs remain local, ignored and untracked.

**Scope limitation:** `btphoneNF.apk` supplies the Geely phone backend and ECARX
wrappers, not the complete stock vehicle Settings implementation or Bluetooth
controller firmware. Calls proven below are within the supplied APKs. A declared
SDK interface, demo call or profile constant is not evidence that the vehicle UI
uses it, or that the installed backend implements it.

#### Implemented command path versus Geely wrapper

The proven backend path is:
`com.neusoft.geely.btphone.nf.BtManagerService.bindSettingService()` ->
`BtManagerService$1.onServiceConnected()` ->
`com.nforetek.bt.aidl.INfCommandBluetooth.Stub.asInterface()` ->
`com.neusoft.geely.btphone.nf.bean.SettingServer.setNfCommandBluetooth()`.
`NfServiceBluetooth.onBind()` supplies
`com.goodocom.gocsdkserver.CommandBluetoothImp`.
Implemented radio operations then call
`com.goodocom.gocsdkserver.GocsdkCommandSender`, whose private
`write(String)` delegates to `GocsdkService.write(String)`.

The stock backend creates `Phone`, `NfPbap`, `SettingServer` and `Audio` during
`BtManagerService.init()`. Its explicit HFP/PBAP/Bluetooth binds use flag 1
(`BIND_AUTO_CREATE`); Audio also binds A2DP/AVRCP and registers callbacks.
This initialization must NOT be copied into DiPlay's diagnostic.

The following matrix distinguishes interface availability from implementation.
`Nf*` interfaces have the prefix `com.nforetek.bt.aidl.INf`;
`Command*Imp` and `Gocsdk*` have prefix `com.goodocom.gocsdkserver`;
`SettingServer`, `Phone`, `Audio` and `NfPbap` have prefix
`com.neusoft.geely.btphone.nf.bean`. Full names elsewhere in this section remove
ambiguity. Permissions/lifecycle labels are explained immediately after the table.

| Candidate / exact interface or class | Exact relevant methods | Proven stock/SDK call site | Functional or placeholder | Controller effect | Permission/lifecycle | Bidirectional iAP2 byte transport |
| --- | --- | --- | --- | --- | --- | --- |
| `INfCommandBluetooth` / `CommandBluetoothImp` paired inventory | `boolean reqBtPairedDevices()`; `String getBtRemoteDeviceName(String)` | NForetek SDK `com.nforetek.bt.demo.service.BtService$2.reqBtPairedDevices()`; Geely `SettingServer$2.getBtRemoteDeviceName(String)` | Pair request implemented; name reads cached lookup | Pair request clears cache and calls `GocsdkCommandSender.getPairList()`; name lookup does not request a controller lookup | NForetek service boundary; do not mistake request for getter | No; device metadata, not payload I/O |
| `INfCommandBluetooth` / `CommandBluetoothImp` discovery and bonding | `boolean startBtDiscovery()`; `boolean cancelBtDiscovery()`; `boolean reqBtPair(String)`; `boolean reqBtUnpair(String)` | Matching NForetek `BtService$2` forwarding methods; corresponding Geely `SettingServer$2` methods return false instead of forwarding | NForetek implemented; those Geely wrapper methods are placeholders | Calls `startDiscovery()`, `stopDiscovery()`, `paireDevice(String)` and `deletePair(String)` on sender respectively; pairing/discovery also produces callbacks | NForetek service boundary; mutates radio/bond state | No |
| `INfCommandBluetooth` / `CommandBluetoothImp` phone connection and UUID/profile mask | `int reqBtConnectHfpA2dp(String)`; `int reqBtDisconnectAll()`; `int getBtRemoteUuids(String)` | Matching NForetek `BtService$2` methods; Geely `SettingServer$2` connect/disconnect return 0 without forwarding | Connect/disconnect implemented; UUID method logs then returns constant 39 | Connect may disconnect an existing phone before `connectDevice(String)`; disconnect calls `disconnect()`; UUID method sends no query | NForetek service boundary; connection commands can disrupt current phone | No; HFP/A2DP connection is not a serial channel; 39 is not measured SDP support |
| Geely `com.ecarx.xui.adaptapi.bt1.settings.IBtSettings` / `SettingServer$2` cached inventory | `List reqBtPairedDevices()`; `boolean reqBtPairedDevicesAsync()`; cached name/address/state getters | `BtManagerService$5.getBtSettings()` returns this implementation; `$2$1.run()` returns cached list to `IBtSettingsCallback.onPairedDevicesChanged(List)` | Cached list access implemented; async posts callback and returns false | These list bodies send no new pair-list request; obtaining/initializing wrapper can register NForetek callback and cause effects below | Exported manager boundary; never auto-create manager | No |
| `INfCommandHfp` / `CommandHfpImp`; Geely `Phone$2` / `com.ecarx.xui.adaptapi.bt1.hfp.IHfp` | `String getHfpConnectedAddress()`; `int getHfpConnectionState()`; `boolean isHfpConnected()`; `boolean reqHfpConnect(String)`; `boolean reqHfpDisconnect(String)` | `BtManagerService$1` resolves HFP Stub, `Phone.setNfCommandHfp()` registers callback; `Phone$2` forwards status reads | NForetek getters/connection operations implemented; Geely `Phone$2.reqHfpConnect/Disconnect` return false | Listed NForetek getters read cache; NForetek connect/disconnect reach sender; other HFP call/audio methods issue telephony/controller/audio work | Separate HFP bind, shared backend destroy hook; not currently authorized | No; call control/SCO audio, not arbitrary peer bytes |
| `INfCommandA2dp`, `INfCommandAvrcp` / `CommandA2dpImp`, `CommandAvrcpImp` | `String getA2dpConnectedAddress()`; `int getA2dpConnectionState()`; `boolean isA2dpConnected()`; `boolean reqA2dpConnect(String)`; `boolean reqA2dpDisconnect(String)`; `boolean reqAvrcp13GetElementAttributesPlaying()`; `boolean reqAvrcp13GetPlayStatus()`; `boolean reqAvrcpPlay()`; `boolean reqAvrcpPause()` | Geely `Audio$1` resolves interfaces/registers callbacks; `$4` forwards A2DP; `$6` forwards AVRCP; `$2.onAvrcp13EventTrackChanged()` requests metadata | A2DP connection/cache and several AVRCP media methods implemented; do not assume every SDK method works | A2DP connect/disconnect call sender; AVRCP metadata/play-status/play/pause call `getMusicInfo()`, `inqueryA2dpStatus()`, `musicPlay()`, `musicPause()` | Separate profile binds, shared destroy hook; render/volume/audio operations also non-passive | No; audio/media control is not general serial transport |
| `INfCommandPbap` / `CommandPbapImp`; Geely `NfPbap$2` / `com.ecarx.xui.adaptapi.bt1.pbap.IPbap` | `int getPbapConnectionState()`; `String getPbapDownloadingAddress()`; `boolean reqPbapDownload(String,int,int)` | `BtManagerService$1` resolves PBAP Stub; `NfPbap.setNfCommandPbap()` registers callback; `$2` forwards download/state methods | Phonebook download/status implementation exists | Download reaches phonebook-update commands; not a passive getter or byte pipe | Profile bind/shared destroy; vendor contacts/call-log access does not transfer to DiPlay | No; structured phonebook transfer |
| `INfCommandSpp` / `CommandSppImp` and ECARX `ISpp` | All eight exact SPP methods in the verified transaction table above | SDK `BtService$1` registers; `BtService$2` forwards connect/list/send; `BtService.onDestroy()` unregisters; Geely `BtManagerService$5.getSpp()` returns null | Installed NForetek SPP implementation is entirely no-op/false; Geely wrapper absent | Actual SPP Binder bodies send nothing, but binding/creating/destroying service affects shared backend | Explicitly forbidden `NfServiceSpp`; no bind/start/use | Interface could describe bytes in another implementation, but this installed one cannot provide them |
| Internal `GocsdkCommandSender`, `GocsdkService`, `com.goodocom.gocsdk.SerialPort` | `void connectSpp(String)`; `void disconnectSpp()`; `void sppSendData(String,String)`; `void write(String)`; JNI `open(String,int,int)`, `close()`, `newdata(String)` | Radio/profile command implementations and `GocsdkService$SerialThread`; no DEX invocation of those three SPP sender helpers found | Serial/controller channel implemented; SPP helpers are unconnected to public SPP implementation | Writes controller command frames; `connectSpp(String)` even ignores supplied address | Internal device/socket access; not an exported peer transport and must not be instantiated in DiPlay | Not established; text controller link does not prove lossless arbitrary peer byte I/O |
| ECARX alternate profiles / Geely phone manager | `getGattServer()`, `getHid()`, `getMap()`, `getSpp()` | `com.neusoft.geely.btphone.nf.BtManagerService$5` implementations | Each returns null | No operation from these getter bodies | Manager creation still has independent initialization effects | No accessible alternate byte transport established |
| `com.nforetek.bt.aidl.INfCommandGattServer` / `CommandGattServerImp` | `boolean reqGattServerSendNotification(String,int,ParcelUuid,ParcelUuid,boolean,byte[])`; `boolean reqGattServerSendResponse(String,int,int,int,byte[])` | No usable Geely wrapper: manager `getGattServer()` returns null | Both byte-send methods return false; callback register/unregister only maintain a `RemoteCallbackList` | No controller/peer send in these bodies | Not an authorized service path; registration alone does not implement GATT | No |

The alternate `CommandHidImp`, `CommandMapImp` and `CommandOppImp` contain
only no-work method bodies beyond construction (no method calls or field access).
They do not supply a missing byte transport. GATT callback-list bookkeeping
likewise does not repair its false-returning send methods.

**Service permission/lifecycle boundary:** NForetek services use targetSdk 22,
enabled=true and intent filters, with no declared component permission or explicit
export attribute; the API-22 filter default exports them. The Geely manager is
explicitly exported/enabled with no declared service permission. Only Bluetooth
flags=0 access and the five cache reads have been hardware-confirmed for DiPlay.
No claim is made that ordinary-app access to other profiles works on the car.
The vendor APK's contacts/call-log permissions are its own permissions, not
requirements automatically inherited by a Binder client.

`NfServiceBluetooth`, `NfServiceHfp`, `NfServiceA2dp`, `NfServiceAvrcp`,
`NfServicePbap` and SPP destruction all call shared `GocsdkService.destroy()`.
An export flag or zero-flag bind does not remove last-client destruction risk.
Creating the singleton starts serial processing and controller startup queries.
No auto-create, direct singleton construction, start/stop or other profile bind
is justified by this static audit.

#### Callback handling: registration is NOT reliably passive

Geely `SettingServer.setNfCommandBluetooth()` registers its genuine
`INfCallbackBluetooth` implementation, `SettingServer$1`.
That implementation stores/forwards adapter state, local name and paired-device
changes through Handler runnables and `IBtSettingsCallback`. Discovery events are
present but the corresponding Geely discovery commands are placeholders.
The NForetek receive parser calls `CommandBluetoothImp.onCurrentAndPairList(...)`,
`onDiscovery(...)`, `onDiscoveryDone()`, `onBtPairModeChanged(...)` and
`onHfpStatus(...)`, among other handlers.

Exact relevant callback contract:

- `void retPairedDevices(int,String[],String[],int[],byte[])`
- `void onDeviceFound(String,String,byte)`
- `void onDeviceBondStateChanged(String,String,int,int)`
- `void onDeviceUuidsUpdated(String,String,int)`
- `void onAdapterStateChanged(int,int)`
- `void onAdapterDiscoveryStarted()` / `void onAdapterDiscoveryFinished()`
- `void onBluetoothServiceReady()`

**New safety blocker:** successful
`CommandBluetoothImp.registerBtCallback(INfCallbackBluetooth)` calls
`onBluetoothServiceReady()`, broadcasting readiness to all registered clients.
When shared `bserviceready` is false, that method sets it true and invokes
`QueryBluetoothInit()`, `setAutoConnect()`, `getVersion()`, `getPairList()`,
`inqueryHfpStatus()`, `inqueryA2dpStatus()`, `getMusicInfo()`,
`inqueryAvrcpStatus()` and `inqueryPbapStatus()`, then starts `GetXdsnThread`.
These are actual sender calls, not interface declarations. Thus callback-only
registration can issue controller commands and alter auto-connect even though
the client requests no scan or connection. The true/false readiness guard is not
one of the five approved remotely accessible cache values.
Do NOT recommend registration as a harmless passive next test.
DEX offsets: registration `0x0b2bec`, readiness handler `0x0b23ac`.

#### Apple/iAP, UUID and SPP references

`com.nforetek.bt.res.NfDef` defines HFP=1, A2DP=2, AVRCP=4, PBAP=32,
SPP=128 and IAP=8192. `CommandBluetoothImp.getBtRemoteUuids(String)` returns
constant 39 (1|2|4|32) regardless of address, not discovered UUIDs.
It cannot establish SPP or iAP capability. Similar profile constants and Apple
callback signatures appear in the ECARX/NForetek SDK copies in both APKs.

`com.nforetek.bt.callback.DoCallbackSpp` forwards SPP readiness/state/data/Apple
events to UI callback lists when invoked. Generated Stub/Proxy code marshals
these interfaces. Neither fact creates a working event producer in
`CommandSppImp`; the actual serial receive parser has no SPP-handler dispatch.
SPP indication/command strings in `com.goodocom.gocsdk.Commands` therefore
remain protocol vocabulary, not a completed transport path.

The embedded NForetek demo is especially unsuitable as a passive reference:
`com.nforetek.bt.demo.service.BtService$9.onSppAppleIapAuthenticationRequest(String)`
constructs a fixed seven-byte array and calls `INfCommandSpp.reqSppSendData`.
This is an attempted Apple-path response in demo code, not evidence of working
authentication on the Geely firmware. Do not copy, invoke or reproduce that
behavior. Geely's own phone-manager SPP getter returns null.
`com.nforetek.bt.demo.ui.SppPageActivity$11` also implements the Apple callback
for demo UI/logging; it is not a Geely transport implementation.
No implemented Apple command interface, iAP2 framing engine, MFi/accessory
implementation, Android `BluetoothSocket`/RFCOMM creation path, or functional
arbitrary peer-byte API was identified in these two APKs.
This negative finding is scoped to these APKs, not the entire vehicle firmware.

#### Native/controller channel and missing stock integration

`GocsdkService$SerialThread.run()` contains an optional reserved local socket
`goc_serial` and a serial-device path `/dev/goc_serial` opened at 115200 baud,
flags=0. The constructor sets `use_socket=false`; the socket branch's presence
does not prove it is active on the car. Outbound writes use command head/tail
framing; inbound bytes are accumulated into CR-terminated strings, with LF
discarded and special-byte escaping before `onSerialCommand(String)` parsing.
This is a Bluetooth controller control/event channel, not an Android peer RFCOMM
socket and not demonstrated as binary-transparent iAP2 transport.

`libserial_goc.so` is packaged for armeabi-v7a, arm64-v8a, x86 and x86_64.
ARM JNI exports are `Java_com_goodocom_gocsdk_SerialPort_open`,
`..._close` and `..._newdata`; inspected imports include open/ioctl.
No iAP/MFi native entry point was identified. `btphoneNF.apk` packages no native
library, but its ECARX/Neusoft `SignVerify` classes declare native signing helpers
and load `signer_verify`, an external dependency not included in the supplied
APKs. Those declarations are not Bluetooth/MFi transport evidence.
Device-node ownership/SELinux policy, socket-server implementation, external
signer library and Bluetooth module firmware were not supplied or executed.

`BtManagerService.initialize()` binds action
`com.neusoft.shockwave.setting.btphonelogic.service.BluetoothControlService`
with flag 1. Its `StateChangedConnection` retains the Binder and links a death
recipient; this does not reveal that external service's command implementation.
The real inventory's Wheeljack Settings services are likewise outside the two
supplied APKs. This missing Settings/control layer is the next useful static
input for proving the vehicle UI's actual discovery, pairing and phone-connection
route; do not infer those operations from stubbed phone-wrapper methods.

#### Safest next hardware test (recommendation only)

Reuse the unchanged Phase 3B.3 five-getter cache diagnostic on a parked vehicle.
With Geely Settings keeping its service active, manually capture ON, OFF and
ON-again reports using the Geely UI for each change. Record bind/descriptor/
genuine interface, name/address, enabled flag, raw/interpreted state, version,
errors/unbind result, and whether Geely phone/audio still works afterward.
Expect 302/true for ON and 300/false for OFF, but report stale/unavailable values
honestly. If the service stops, record bind failure and do not auto-create it.
This tests cache freshness without adding another interface call.

Before expanding hardware scope, obtain the installed Settings/control APK for
local static inspection, particularly the component implementing the action
above. Do not register vendor callbacks, request paired devices, bind SPP,
open the serial node/socket, send controller commands, pair/connect, respond
to Apple requests, authenticate or start Phase 3C. No new hardware test or
runtime transport was implemented as part of Phase 3B.4.

### Phase 3B.4 stock control APK collection build

Build `0.2.12-api22-phase3b4-control-apk-export` adds the manual button
**Find and export stock Geely Bluetooth control APK** beside the existing APK
exporter in **Settings -> Phase 3B.2 NForetek service diagnostics**.
This is APK collection, not a transport diagnostic or Phase 3C.

Only after that button is pressed, a background worker uses PackageManager to
enumerate visible installed package/application and manifest service, receiver,
activity and provider names, including disabled components. Exact matches use
the `com.neusoft.shockwave.setting.btphonelogic` namespace boundary.
A separate metadata-only `queryIntentServices` resolves action
`com.neusoft.shockwave.setting.btphonelogic.service.BluetoothControlService`,
because the actual implementation class/package can differ from that action.
No service is started, bound or invoked; no broadcast is sent.

The screen and saved diagnostic report list each matching package, component/
match reason and installed `ApplicationInfo.sourceDir`. Each exact owning package's
base APK and any installed split APKs are copied and independently verified through
the existing export destination policy. Output names are
`<package>-base.apk` and `<package>-split-<index>.apk`. The report records original
source, destination, byte count, SHA-256 and per-file failures.
It uses `Download/DiPlayVendorDump` when legacy storage permission allows, otherwise
the app external-files `DiPlayVendorDump`; failed public writes remain visible.
No hardcoded system installation path, root, permission prompt or package launch
is used. Source APKs are not modified.

If no exact namespace/class/action metadata match is visible, the report lists
candidate installed packages/components containing `neusoft`, `shockwave`,
`btphone`, `bluetooth`, `setting` or `vehicle`, with source/split paths where
available. Candidates are NOT automatically copied or launched. Enumeration,
source resolution and copy failures are logged and displayed, not treated as
successful discovery. Manifest metadata cannot prove absence of an internal
DEX-only class; Android package visibility restrictions can also limit inventory
on newer devices. API 22 has no modern package-visibility filtering. An exact-action
manifest query improves visibility on newer Android without adding QUERY_ALL_PACKAGES.

The existing two-APK exporter reuses the same copy/hash/destination helper, and
the collection report uses the existing visible/exported APK-report field.
The five-getter Phase 3B.3 cache diagnostic and Phase 3A network logic are unchanged.
No vendor access occurs at startup or Settings construction. No callbacks,
Bluetooth API, serial device/socket, controller query or transport has been added.
Once the APKs are collected locally, stop and inspect them statically first.

### Phase 3B.4 combined audit with the extracted stock Settings APK

The operator successfully exported package
`com.neusoft.optimus.wheeljack.setting`, reported installed at
`/system/app/Setting/Setting.apk`. The local input is
`vendor-apks/com.neusoft.optimus.wheeljack.setting-base.apk`:
85,688,096 bytes, SHA-256
`d56c07989dd85c5c94479a7d8bf86ca6aa7a83bebe3f4f58aaa23e74c8001ed5`.
Its single `classes.dex` is 6,399,912 bytes. Manifest min/target SDK are 22.
This extends, rather than replaces, the two-APK audit above: all three inputs
remain ignored, untracked and local-only. Complete Settings DEX instructions,
manifest, archive entries, generated-interface references and non-generated
call sites were inspected, including `invoke-*/range` calls. No vendor code or
native library was loaded/executed. Only documentation changes in this milestone.

#### Proven stock UI and controller chain

The previously missing control implementation is now available:

- `com.neusoft.shockwave.setting.btphonelogic.service.BluetoothControlService`
  is a policy/lifecycle coordinator. **`onBind(Intent): IBinder` returns null**,
  not a Bluetooth command interface (DEX code item `0x206e18`).
- Its `onCreate()` calls private `bindService()`, `init()` and
  `registerLinDiagCallback()`. The private bind explicitly creates/binds
  `com.neusoft.shockwave.setting.btphonelogic.service.BluetoothService`
  with flag 1 (`BIND_AUTO_CREATE`).
- `BluetoothControlService$1.onServiceConnected()` resolves
  `com.nforetek.bt.aidl.UiCommand.Stub.asInterface(IBinder)`,
  registers its Bluetooth callback and calls `setBtAutoConnect(int,int)`.
  Other policy branches enable/disable Bluetooth, disconnect, rename, unpair,
  read/write settings and interact with vehicle power. Creating this coordinator
  is not a read-only observation strategy.
- `com.neusoft.shockwave.setting.btview.BtSettingFragment.bindBtService()`
  also explicitly binds the same `BluetoothService` with flag 1.
  `$2.onServiceConnected()` resolves `UiCommand`, and `initState()` passes it
  into
  `com.neusoft.shockwave.setting.btphonelogic.specific.bean.BluetoothBean.initialize(Context,UiCommand)`,
  which registers `UiCallbackBluetooth`.
- `BluetoothService.onBind(Intent)` returns `mBinder`, implemented by
  `BluetoothService$2 extends com.nforetek.bt.aidl.UiCommand.Stub`
  (code item `0x2106e8`).
- `BluetoothService.onCreate()` auto-creates/binds exactly the A2DP, AVRCP, HFP,
  PBAP and Bluetooth NForetek services. `$1.onServiceConnected()` resolves their
  genuine `INfCommand*.Stub.asInterface()` implementations and registers callbacks.
  This supplied `onCreate()` does **not** bind SPP, HID, MAP, OPP or GATT despite
  retaining branches/callback objects for these SDK profiles.
- The downstream Bluetooth route is
  `INfCommandBluetooth` -> `com.goodocom.gocsdkserver.CommandBluetoothImp`
  -> `GocsdkCommandSender` -> `GocsdkService` controller serial channel, as
  verified in the original vendor APK. The stock Settings `UiCommand` forwarding
  bodies are functional, unlike the similarly named placeholder ECARX settings
  operations in `btphoneNF.apk`.

In the matrix below, `BluetoothBean`, `BluetoothControlService` and
`BluetoothService` mean the fully qualified classes above.
`INf*`, `UiCommand` and `UiCallback*` are in `com.nforetek.bt.aidl`;
`DoCallback*` here are in `com.neusoft.shockwave.setting.btcallback`.
No method in this matrix is newly authorized for DiPlay.

| Candidate / exact interface or class | Exact methods and stock caller | Implementation and controller effect | Permissions/lifecycle | iAP2 byte-transport assessment |
| --- | --- | --- | --- | --- |
| Stock paired-device inventory through `UiCommand` / `INfCommandBluetooth` | `boolean reqBtPairedDevices()`: `BluetoothBean.acquireDevices()` and `BluetoothControlService.bluetoothOn(BtStateModel)` -> `BluetoothService$2.reqBtPairedDevices()` | Functional forwarding; backend clears its pair cache and issues `getPairList()`, not a cache-only list getter | Stock bridge auto-creates profiles/registers callbacks; request must not be used as passive inventory | Device metadata only |
| Stock discovery and bonding through `UiCommand` / `INfCommandBluetooth` | `boolean startBtDiscovery()`, `boolean cancelBtDiscovery()`, `boolean reqBtPair(String)`, `boolean reqBtUnpair(String)`: `BluetoothBean.searchDevices(boolean)`, `$1.onDeviceFound(String,String,byte)`, `connect(int)`, `unpair(int)`, `disconnectUnpair(String)` -> matching `BluetoothService$2` forwarding methods | Implemented controller discovery, cancellation, pairing and bond deletion; `connect(int)` branches between pair and connection requests | Changes radio/bond state; callback registration is not reliably passive | No payload channel |
| Stock phone connection through `UiCommand` / `INfCommandBluetooth`, `INfCommandHfp`, `INfCommandA2dp` | `int reqBtConnectHfpA2dp(String)`: `BluetoothBean.connect(int)` / `connectLastDevice()`; `boolean reqHfpConnect(String)`: `BluetoothControlService.startConnect(String)` after `cancelBtDiscovery()`; `int reqBtDisconnectAll()`: control receiver; corresponding `BluetoothService$2` delegates | Functional downstream connection/controller operations, potentially disconnecting an existing phone | Profile creation/callbacks and connection changes; do not bind the coordinator/bridge as a shortcut | HFP/A2DP/SCO is not arbitrary bidirectional bytes |
| Stock profile status through `UiCommand` | `int getHfpConnectionState()`, `int getA2dpConnectionState()`, `int getAvrcpConnectionState()`; `String getHfpConnectedAddress()`, `getA2dpConnectedAddress()`, `getAvrcpConnectedAddress()`: `BluetoothControlService.updateBluetoothState()` / `$3.run()` and matching bridge getters | Cached profile state/address forwarding where implemented by the audited backend; separate from pair-list/controller requests | Getting to the stock bridge may initialize profiles/policy; these additional interfaces remain outside the approved five-getter test | Status only |
| `INfCallbackBluetooth` -> stock `UiCallbackBluetooth` | `BluetoothService$8.onAdapterStateChanged(int,int)`, `onDeviceFound(String,String,byte)`, `onDeviceUuidsUpdated(String,String,int)`, `retPairedDevices(int,String[],String[],int[],byte[])` -> `DoCallbackBluetooth` -> `UiCallbackBluetooth`; consumers `BluetoothBean$1` and `BluetoothControlService$2` | Real callback fanout and stock model/list/EventBus updates; HFP/A2DP/AVRCP callbacks similarly feed UI state. Event handling can trigger policy/controller work | `registerBtCallback()` downstream can run controller initialization, queries and auto-connect; not approved as read-only | Structured metadata/events, not peer byte packets |
| Stock SPP bridge and Apple callback: `INfCommandSpp`, `INfCallbackSpp`, `UiCallbackSpp` | `BluetoothService$2.reqSppConnect(String)`, `reqSppDisconnect(String)`, `reqSppConnectedDeviceAddressList()`, `reqSppSendData(String,byte[])`, `isSppConnected(String)`, `isSppServiceReady()` delegate if interface non-null; `$9.onSppDataReceived(String,byte[])` -> `DoCallbackSpp`; `$9.onSppAppleIapAuthenticationRequest(String)` attempts `reqSppSendData` with a fixed seven-byte response | Forwarding/callback bodies exist, but normal stock startup has no SPP bind; installed `CommandSppImp` is still entirely false/no-op and produces no usable byte stream. The Apple callback is an attempted response, not proof of working authentication | Never bind/start/use `NfServiceSpp`; shared-backend destroy hazard still applies. Do not invoke/copy the Apple response | Contract describes bytes, but supplied installed implementation cannot provide them |
| Manifest BLE services: `com.neusoft.shockwave.setting.btphonelogic.service.BluetoothBleService`, `BluetoothBleServiceNF` | Manifest names/actions only in this supplied APK; no corresponding class definitions in its single DEX | Implementation unavailable, not a proven functional BLE fallback. No Android `BluetoothGatt`, `connectGatt`, `BluetoothSocket` or RFCOMM creation reference found in Settings DEX | Exported/persistent declarations without service permission do not prove executable classes, ordinary-app access or safe lifecycle | No verified GATT/serial or iAP2 path |
| Internal `GocsdkService`, `GocsdkCommandSender`, `com.goodocom.gocsdk.SerialPort` | Existing `write(String)`, `connectSpp(String)`, `disconnectSpp()`, `sppSendData(String,String)` vocabulary; native `open`, `close`, `newdata` in `libserial_goc.so` | Implemented controller command/event link; SPP sender vocabulary is not wired through the installed SPP Binder/parser | `/dev/goc_serial`, 115200 baud, flags 0; optional reserved socket `goc_serial`, constructor `use_socket=false`. Privileged device/socket access and shared lifecycle; never open it from DiPlay | Not demonstrated as binary-transparent peer/iAP2 bytes |
| Separate `com.neusoft.applecore.AppleInterface` / `com.neusoft.appleservice.ApplePrivate` / `AppleService` | `CarPlayDialog.onCreate()` -> `AppleInterface.init()/getInstance()`; `CarPlayDialog$4.run()` -> `setDefaultMode(int)` with 0/1. `AppleService` -> USB/native methods listed below | Java JNI wrappers and USB state machine exist, not inert placeholders; native implementation and live capability are unverified because libraries are absent from all supplied APKs | Native initialization, USB role changes, network-interface changes and authentication listeners; stock system privilege cannot be assumed by a sideloaded app | Stronger Apple/USB implementation lead, but no exposed general iAP2 send/receive API or proven Bluetooth transport |

#### Control intents, callbacks and privileged boundaries

The Settings manifest uses `sharedUserId=android.uid.system` and requests
`BLUETOOTH_PRIVILEGED`, `BLUETOOTH`, `BLUETOOTH_ADMIN` and other system permissions.
Its exported control/bridge/BLE service declarations have no declared service
permission in the inspected blocks. This is metadata, not a hardware proof of
ordinary-app binding, successful class loading or safe lifecycle. The coordinator
returns null even when its implementation is available.

`BluetoothControlService.initReceiver()` subscribes to:
`com.neusoft.optimus.preceptor.disconnect`, `com.neusoft.setting.bt.enable`,
`com.neusoft.setting.bt.disable`, `com.neusoft.geely.factoryreset`,
`com.neusoft.geely.callstatechanged`,
`com.neusoft.geely.bluetooth.getmacaddress` and `BluetoothEnabledState`.
These are policy/request inputs, not an authorization to send broadcasts.
The MAC request branch reads `getBtLocalAddress()` and sends
`com.neusoft.geely.linediag.broadcast`; other branches perform enable/disable,
disconnect or factory-reset work. State-related outputs include
`com.neusoft.geely.bluetooth.setting.state`,
`com.neusoft.geely.bluetooth.power.state` and
`com.neusoft.setting.bt.sdkVersion`. No new receiver/listener is added to DiPlay.

The coordinator also binds package `com.neusoft.shockwave.tboxclient`, action
`com.neusoft.shockwave.tboxclient.XCallInfoAidlInterface`, reads hidden property
`persist.bt.active`, and uses external `com.neusoft.alfus.RpcManager`:
`registerListener(int,byte,OnRpcListener)` with ID 1560 / type 14,
and `sendMessage(int,byte[])` with ID 1914 in activation success/failure paths.
These are vehicle integration, not an Apple peer-byte transport. Their external
framework/controller implementation is not supplied here.

`BluetoothDiscoverableTimeoutReceiver.onReceive(Context,Intent)` is a one-instruction
no-op (`0x202ba0`), despite the manifest
`android.bluetooth.intent.DISCOVERABLE_TIMEOUT` filter and alarm helpers.
No exact `BluetoothReceiver` class definition was found in the supplied Settings
DEX. BLE declarations and inventory names alone must not be reported as inspected,
functional implementations; external framework/split/firmware code remains possible.

#### Separate Apple native / USB path and where tracing stops

`AppleInterface` loads **`AppleCore_jni`** and its constructor calls native init.
Exact native boundaries include:

- `int appleCore_native_init()`, `int appleCore_native_attachServer()`,
  `int appleCore_native_detachServer()`;
- `String appleCore_native_appledevice_connected()`;
- `int appleCore_native_getUSBInfo(String, com.neusoft.applecore.USBInfo)`;
- `String appleCore_native_getConfig(String)`;
- `int appleCore_native_setDefaultMode(int)`;
- `int appleCore_native_requestAppLaunch(String,int)`;
- `int appleCore_native_sendPlaybackRemoteCmd(byte[])`.

The last method is typed media-remote work, not evidence of general peer-byte I/O.
`ApplePrivate` loads **`ApplePrivate_jni`**; exact native boundaries include
`int applePrivate_native_init()`, `applePrivate_native_open()`,
`applePrivate_native_close()`, `applePrivate_native_attachServer()`,
`applePrivate_native_detachServer()`, `applePrivate_native_switch_host_mode()`;
`int applePrivate_native_setMode(int)`;
`int applePrivate_native_switch_device_mode(String)`; and
`int applePrivate_native_getUSBInfo(String, com.neusoft.applecore.USBInfo)`.
Apparent wrapper getters must not be called by DiPlay: initialization itself
loads/initializes native code and can have side effects.

The embedded `AppleService.onCreate()` initializes both native facades, sets mode,
registers `AppleInterface.OnAuthenticationListener` and a service-death listener,
finds a device and starts `UEventObserver` monitoring. Its USB attach flow reads
`DEVTYPE=usb_device`, `ACTION=add`, `DEVPATH`, `DEVNUM`, `BUSNUM` and `DEVNAME`,
constructs **`/dev/` + `DEVNAME`**, and calls
`getUSBInfo`, `USBInfo.isCarPlaySupport()` and `switchDeviceMode(String)`.
Configured/disconnect paths call `open()` / `close()`.
`AppleService$1.OnAuthentication(int,int)` posts authentication state into its
handler; this is not an SPP authentication response bridge.

Actual USB/OTG/audio UEvent paths come from native `getConfig(String)` keys
`USB_DEVPATH_ROOT`, `OTG_SWITCHED_DEVPATH`, `HOST_AUDIO_DEVPATH`; their values
are not present in the supplied implementation. `NCMAutoUpDown` controls
network policy. `ApplePrivate.setInterface(int)` uses hidden
`ServiceManager.getService("network_management")` ->
`INetworkManagementService.Stub.asInterface()` and changes **`usbncm0`**
interface configuration/up/down state (`0x19c03c`).
`AppleDevice.checkProperty()` references `persist.neusoft.Apple.mode`.
Outputs are `com.neusoft.apple.device.attached`,
`com.neusoft.apple.device.connected`, `com.neusoft.apple.device.disconnected`.
This is concrete USB/OTG/NCM-oriented Apple integration, not a proven
NForetek Bluetooth/iAP2 route.

The embedded `com.neusoft.appleservice.BootCompletedReceiver.onReceive()` attempts
to start action **`com.neusoft.appleservice`**, explicitly targeting package
**`com.neusoft.appleservice`**. This is a separate target-package reference,
not proof of an installed package. Embedded service classes alone do not prove
they are active components of Settings; the subsequent Phase 3B.5 hardware
inventory below did not find that package.

There are **no `.so` entries anywhere in the Settings APK or btphoneNF APK**.
The original NForetek APK supplies only its `libserial_goc.so` ABI variants,
not either Apple JNI library. Static tracing stops at these external native
boundaries: native server descriptors, sockets, configuration filenames,
device ownership, iAP/iAP2 implementation, authentication/MFi hardware,
USB protocol and any Bluetooth connection cannot be determined from JNI names.
No Android peer RFCOMM creation, canonical UUID string, explicit iAP2 engine,
or implemented NForetek-to-Apple byte handoff was found in Settings DEX.
Profile constants remain HFP=1, A2DP=2, AVRCP=4, PBAP=32, SPP=128, IAP=8192;
the installed `getBtRemoteUuids(String)` still returns constant 39, not SDP.
Negative findings apply to supplied artifacts, not all vehicle firmware.

#### Safest concrete next real-hardware test

Keep the existing five-getter cache-only diagnostic unchanged. Park the vehicle,
keep stock Geely Settings active, and capture ON -> OFF -> ON-again by changing
Bluetooth only in the stock UI. After each change press DiPlay's existing cache
status button once. Save raw/interpreted values, version, flags=0 bind/descriptor/
genuine interface/unbind and errors. Compare expected 302/true and 300/false;
record stale/unavailable results. Do not auto-create a stopped service.
Check stock phone/audio behavior afterward without initiating a new DiPlay connection.

The next useful *collection*, before any transport test, is read-only installed
metadata/source APK collection for **`com.neusoft.appleservice`**, and the actual
readable **`libAppleCore_jni.so`**, **`libApplePrivate_jni.so`** and their native
dependencies/configuration. Resolve APK paths using PackageManager (or `pm path`
if an already-authorized ADB connection exists), and library locations from
package/framework metadata or a read-only filesystem inventory. Do not assume
a hardcoded system path, load the libraries, invoke getters, start services,
change permissions/root the unit or bypass an access denial. Preserve size/hash,
ABI, original path and any inability to read them. Stop after collection for
another static audit. No collector extension is implemented in this milestone.

Do not bind/start the stock control/bridge/BLE services or SPP; register callbacks;
send policy broadcasts/controller commands; open serial/socket/USB nodes;
initialize Apple JNI; pair/connect; send payloads or answer Apple authentication.
**No usable iAP2 transport is established; Phase 3C remains blocked.**

### Phase 3B.5 Apple/USB stack collection build

Build `0.2.12-api22-phase3b5-apple-stack-export` adds **Phase 3B.5 Apple/USB
stack collection only** in Settings. The manual **Collect and export Apple/USB
stack files** button reuses the existing background export worker, destination
policy and visible/saved vendor-export report. Construction, launch and lifecycle
do not access the Apple package, library directories, manifest or native files.
Existing Phase 3A networking and Phase 3B.3 cache status remain unchanged.

Only after the operator presses the button:

- PackageManager resolves `com.neusoft.appleservice` and records version name/code,
  `sourceDir`, `splitSourceDirs`, `nativeLibraryDir`, `sharedLibraryFiles`, exported
  services/receivers/providers and relevant non-exported Apple/USB components.
  Records include enabled/exported state, permissions and provider authorities.
  A public package-resources manifest parser records component/action names matching
  Apple, USB, iAP, CarPlay, NCM, accessory or authentication terms; no intent is sent
  or service resolved/invoked. Parser/access failures are explicit. PackageManager
  includes split components, but this public manifest parser does not promise
  enumeration of every split's intent filter.
- Base and all reported split APKs are copied independently. Missing/inaccessible
  package/base/splits are failures, not successful or complete collection.
- Library search roots are absolute, readable `nativeLibraryDir`, parents of
  `sharedLibraryFiles`, and the public Java `java.library.path`. No hardcoded
  `/system/app/...` APK or guessed library directory is used, no recursive filesystem
  traversal occurs, and relative search directories are rejected.
- Exact `libAppleCore_jni.so` / `libApplePrivate_jni.so` matches in those directories
  are copied. Exact `lib/<ABI>/<name>` entries in the resolved APKs are also exported
  using bounded private temporary extraction, verified publication and cleanup.
  Archive paths cannot traverse directories.
- A read-only ELF parser handles 32/64-bit, little/big-endian headers and
  file-backed `PT_LOAD` / `PT_DYNAMIC` mappings. It records ELF architecture and
  genuine `DT_NEEDED` names, not guessed dependency IDs. ARM32 is not claimed to prove
  ARMv7 instruction compatibility; APK entry paths separately record Android ABI.
  Direct dependencies of the two roots are collected from the same metadata/search
  directories or matching-architecture APK library entries. Absolute dependency
  paths must have a parent in the resolved library-directory set. Architecture
  mismatches, malformed ELF and unresolved names are visible failures.
  No recursive/transitive dependency expansion is performed.
- Bounded printable-string inspection of roots and collected direct dependencies
  identifies `.conf`, `.cfg`, `.ini`, `.xml`, `.json`, `.properties` references.
  These are explicitly **config-reference candidates**, not verified active config.
  Relative names resolve only inside the known library directories; exact absolute
  references must remain in those roots or system/vendor configuration scope.
  No path is guessed from config keys such as `USB_DEVPATH_ROOT`; no directory-wide
  config harvesting or config-content logging occurs. Unresolved or denied files
  remain explicit in the report.

File source/destination, copied byte count and SHA-256 are reported for every
successful copy. Public `Download/DiPlayVendorDump` is used under the existing
legacy storage policy, with the existing app-external `DiPlayVendorDump` fallback
and visible public-destination failure. Names are:

- `com.neusoft.appleservice-base.apk`, `com.neusoft.appleservice-split-<index>.apk`;
- `apple-stack-dir-<directory-index>-<library-name>`;
- `apple-stack-archive-<APK-index>-<ABI>-<library-name>`;
- `apple-stack-config-<reference-index>-<match-index>-<config-name>`.

All indexes map to source paths in that run's report. The report's SUCCESS lines,
not these naming patterns, are the authoritative exact exported-file list.
The shared copy helper verifies bytes/hash before publication and does not delete
an old destination when publication fails. Existing base-only Bluetooth/control
exports retain their prior behavior.

ELF bounds are explicit: at most 1024 program headers, 65536 dynamic entries per
table, 256 `DT_NEEDED` entries, 4096-byte reference strings, a 32 MiB config-string
scan and 128 config candidates per library; APK native extraction is capped at
64 MiB per entry / 64 matches per lookup. Limit failures never assert a complete
stack, and successfully copied roots/APKs are retained for PC static inspection.

Device/kernel paths `/dev`, `/proc`, `/sys` are rejected before file reads and
after canonical symlink resolution. Directory-library symlinks leaving their
resolved parent are rejected. No native code/class is loaded, no Bluetooth/USB API,
service bind/start/stop, broadcast, callback registration, role change, iPhone
connection, payload or authentication operation is added. Log tag:
`DiPlayPhase3B5Device`; config contents are never logged.

This build is a collector, not another transport/status test. After manual
collection, save the diagnostic report, copy only its successful exports to the
ignored local `vendor-apks/` folder, and **STOP for PC static inspection**.
Actual package/library availability and exact car export paths are not known
until the operator runs this on the Okavango. No Phase 3C is authorized.

### Phase 3B.5b read-only Apple implementation discovery

The operator's Phase 3B.5 real-car collection returned
`NameNotFoundException` for `com.neusoft.appleservice`, no
`libAppleCore_jni.so` / `libApplePrivate_jni.so`, readable
`/system/vendor/lib64` and `/system/lib64`, and **zero collected source files**.
These results invalidate assuming that the Java SDK's target-package/library
names identify the active vehicle implementation. They do not prove absence of
Apple support elsewhere or in 32-bit library directories.

Build `0.2.12-api22-phase3b5b-apple-discovery` adds the distinct manual button
**Phase 3B.5b Apple implementation discovery**. It reuses the existing worker,
vendor-export screen/report and verified `DiPlayVendorDump` copy helper.
The older exact-name collector and cache diagnostic remain unchanged.
Nothing runs during launch, Settings construction or lifecycle.

Only after the button press, installed-package/component/APK-name candidates
are identified using case-insensitive substring terms:
`apple`, `carplay`, `iap`, `iphone`, `ipod`, `usb`, `ncm`, `projection`,
`phone`, `link`, `mirror`, `ecarx`, `neusoft`.
Candidate reports include version, system-app flag, base/split sources, native
directory, matching application/service/receiver/provider/activity names,
component enabled/exported state and permissions/authorities.
Disabled components are included. Substring matches can be unrelated:
neither a candidate nor an export is a proven Apple/iAP implementation.

Public `getResourcesForApplication()` / manifest XML metadata are inspected for
every visible installed package, including **system packages whose package/APK
names do not match**. Matching component/action names can make such a package a
candidate. Readable base/split paths from installed metadata are also inspected
through `getPackageArchiveInfo()` for matching package/components, never through
class loading or vendor code execution. Reported APK paths are checked for forbidden
device/kernel sources before package resources are opened. Null/unsupported split archive metadata,
parser restrictions, inaccessible source files and vendor framework failures
are displayed/logged and do not stop other packages/directories.
The public package manifest parser does not promise every split's intent-filter
coverage; exported split APKs permit complete PC static inspection.
Only installed metadata-resolved system APKs are inspected: there is no recursive
scan of arbitrary APK/storage directories or guessed system installation path.
API 22 installed inventory is not subject to modern package visibility; newer
Android may restrict results. No QUERY_ALL_PACKAGES permission is added.

Native inventory explicitly includes both 32/64-bit locations:
`/system/lib`, `/system/lib64`, `/vendor/lib`, `/vendor/lib64`,
`/system/vendor/lib`, `/system/vendor/lib64`, plus every visible installed
package's `ApplicationInfo.nativeLibraryDir`, irrespective of package-name match.
Canonical directory aliases are deduplicated with all reported origins retained.
Only immediate filenames are enumerated; matching names contain
`apple`, `carplay`, `iap`, `mfi`, `usb`, `ncm`, `accessory`, `projection`.
There is no recursion, ELF/dependency scan, binary string search or native loading
in this discovery action. Matching directories/non-regular/unreadable files are
reported but not copied. A native filename's architecture is not guessed.

Ordinary readable candidate base/split APKs and matching native files are copied
independently with exact source, destination, bytes and SHA-256. Output names:
`apple-discovery-<package>-base.apk`,
`apple-discovery-<package>-split-<index>.apk`,
`apple-discovery-native-<directory-index>-<filename>`.
The directory index/source map and SUCCESS lines in that run's report are
authoritative. Duplicate sources reuse the prior verified path/hash; failed
copies do not count as exports. Broad candidates may include large Settings/
phone/Neusoft APKs; the UI warns that several large APKs may be copied.
Existing legacy public Downloads/app-external fallback is unchanged.
Device/kernel paths and unsafe/out-of-directory native symlink targets are
rejected; no `/dev/*`, `/proc/*`, `/sys/*` file is read.
Log tag is `DiPlayPhase3B5bDevice`. STOP after inventory/export for PC analysis.

#### Settings DEX: embedded classes are not a self-contained Apple stack

The fingerprinted extracted Settings APK actually defines:

| Exact class | Definition / load boundary | Condition and verified callers |
| --- | --- | --- |
| `com.neusoft.applecore.AppleInterface` | Java class extends `Object`; `<clinit>()` code item `0x19a294`, instruction `+0x0009`: `System.loadLibrary("AppleCore_jni")`; no branch or exception handler | **Unconditional on first class initialization**, not merely DEX presence. `init()` (`0x199d0c`) constructs the singleton only when absent; constructor invokes `appleCore_native_init()`. `getInstance()` is also an active class use, not a safe availability probe. |
| `com.neusoft.appleservice.ApplePrivate` | Java class extends `Object`; `<clinit>()` `0x19c2b8`, instruction `+0x0009`: `System.loadLibrary("ApplePrivate_jni")`; no branch or exception handler | **Unconditional on first class initialization**. `init()` (`0x19bfc4`) creates an absent singleton; constructor (`0x19c2e4`) calls `applePrivate_native_init()`. The embedded `AppleService.onCreate()` calls this before initializing `AppleInterface`. |
| `com.neusoft.appleservice.AppleService` | Java class extends `android.app.Service`, implements `Runnable`; code item `onCreate()` `0x19d29c` initializes ApplePrivate, sets mode, initializes AppleInterface, subscribes to native authentication/death events and USB observers | Depends on both external JNI implementations and system/hidden Android facilities, not self-contained Java. **Not declared as a service in the supplied Settings manifest**. No runtime invocation was performed to test it. |

The Wheeljack Settings APK contains these definitions in its DEX and has **no
native `.so` entries**. Phase 3B.7 also found bytecode copies of
`AppleInterface`, `ApplePrivate`, `AppleService` and `BootCompletedReceiver` in
the setting-widget APK; it too has no native `.so` entries. These are duplicated
Java wrappers/service code, not evidence of a separately installed or runnable
Apple service. `ApplePrivate.setInterface(int)` also relies on hidden
network-management APIs; `AppleService` uses `UEventObserver`. Thus "class
present" does not mean "self-contained", installed standalone package, working
native transport or a safely callable getter. No fallback library name,
alternate native implementation or usable iAP2 transport is proven.

Verified caller conditions:

- `com.neusoft.shockwave.setting.btview.ConnectHomeFragment.onClick(View)`
  (`0x2198a8`) selects view ID `0x7f060081`; if `isDismissed=true`, it creates/shows
  `CarPlayDialog`. `CarPlayDialog.onCreate(Bundle)` calls
  `AppleInterface.init()` / `getInstance()` **without a library-availability guard**.
  This is a stock UI branch, not evidence that the hardware enters it or succeeds.
  Do not exercise the stock CarPlay dialog as a library test.
- Embedded `com.neusoft.appleservice.BootCompletedReceiver.onReceive()` only enters
  its Apple branch when action equals `android.intent.action.BOOT_COMPLETED`.
  It calls `AppleInterface.init()` **before** reading native config
  `AutoStartService`; config `"0"` suppresses starting the service, but does not
  suppress the earlier class/library initialization. This receiver is **not
  registered in the supplied Settings manifest**.
- Embedded `AppleService.onCreate()` directly invokes both initialization paths.
  Its Java definition does not establish a registered/reachable Settings component.

#### Every exact `com.neusoft.appleservice` string use

The exact literal has these verified uses in the complete Settings DEX:

1. `com.neusoft.applecore.AppleConstants.INTENT_APPLE_SERVICE`:
   public static final String value `com.neusoft.appleservice`.
2. `BootCompletedReceiver.onReceive()` code item `0x19d6c4`, instruction
   `+0x002d`: used with `new Intent(String)` as an **intent action**.
3. Same method, instruction `+0x0032`: used with `Intent.setPackage(String)`
   as an **explicit target package**, followed by `Context.startService(Intent)`.
   This describes vendor bytecode only; DiPlay never sends this intent.

Separately, `com.neusoft.appleservice.*` is the Java namespace of the embedded
ApplePrivate/AppleService/AppleDevice/BootCompletedReceiver classes. A Java
namespace is not Android package registration. The Wheeljack Settings manifest package is
`com.neusoft.optimus.wheeljack.setting`; it contains no AppleService or
Apple BootCompletedReceiver registration and no exact action filter for
`com.neusoft.appleservice`. The setting-widget manifest likewise has no
AppleService or Apple BootCompletedReceiver registration. Therefore this is an
embedded SDK/legacy/external target reference, **not proof of an actual installed
Android package or service**.
Given the real-car NameNotFound result, the old target is unavailable to this
app on this unit. Whether it is a disabled/removed firmware feature, optional
product variant or renamed implementation is unresolved; discovery must not
choose one explanation by assumption.

No service/intents/broadcasts, native loading, USB role change, Bluetooth
commands, iPhone connection or authentication is added. **STOP after inventory/
export; Phase 3C and transport work remain disabled.**

Build `0.2.12-api22-phase3b2-nforetek-discovery` adds a separate manual inspection and bind
section. Nothing runs at startup. Inspection records exact known service components,
exported/enabled/application state, required permissions, permission protection level
and whether DiPlay currently holds each permission. Eligibility is predictive only:
an exported unprotected component can still reject `onBind` or require a different intent.

Readable APKs and relevant PackageManager-reported shared-library files are inspected
through public `DexFile`/`DexClassLoader` APIs (deprecated on newer Android but available
on API 22). Interface/AIDL/Stub/Proxy candidates are loaded with `Class.forName(...,
initialize=false)`. Public declared method signatures and type/interface metadata are
listed; no class is instantiated, static field value read, `asInterface` called, method
invoked or vendor/native library explicitly loaded. Optimization files may be written
only in DiPlay's private code cache. Missing dependencies, unreadable APKs, unavailable
DEX APIs or linkage errors are reported; no hidden-API/classloader bypass is used.
The class list is bounded and ART-reported entries are not an exhaustive decompilation.

### Phase 3C.3A — Android 5.1 direct USB compatibility

This phase ports the existing wired USB path to the Android 5.1 / API 22 platform
without enabling its runtime connection gate. It does not connect to an iPhone,
perform MFi authentication, activate Bluetooth/NForetek/QDrive, or alter the
existing Phase 1/2/3 launch behavior.

**API 22 incompatibilities addressed**

- `UsbRequest.queue(ByteBuffer)` and timed `UsbDeviceConnection.requestWait(long)`
  are used only on their supported API levels. `UsbTransferCompatibility` dispatches
  to the API 22 `queue(ByteBuffer, int)` and blocking `requestWait()` overloads.
- API 22 has no timed `requestWait()`. The compatibility helper schedules cancellation
  for a blocked request, drains the cancelled request with the blocking wait, and
  reports timeout separately from a session close. Closing a session cancels a pending
  read and closes the USB connection to unblock the wait.
- Before API 28, individual bulk transfers are capped at 16,384 bytes. Logical writes
  are split into bounded transfers and continue across positive partial-write results;
  a no-progress/error result is returned so the caller cannot mistake a truncated
  USBMUX packet or NCM NTB for a complete write.
- Lockdown Base64 now uses Android `Base64` through `Base64Compat` rather than
  `java.util.Base64`, which is not an Android framework API on API 22. Standard
  no-wrap encoding, whitespace-tolerant decoding, and 64-character PEM line wrapping
  are preserved. No certificate, key, PairRecord or authentication check was changed.
- UTC timestamp encoding in the wired iAP2 location client now uses `Calendar`
  rather than API 26 `java.time`. USB permission PendingIntent flags omit
  `FLAG_IMMUTABLE` on API 22 and retain it on API 23+.
- Lockdown TLS no longer invokes API 24's endpoint-identification setter on API 22;
  the explicit `null` setting is retained behind an API 24+ check, while the legacy
  engine default remains unset.

**USB fragmentation and reassembly**

- USBMUX reads are bounded per transfer; the existing `UsbMuxFrameBuffer` retains
  incomplete frames and emits only complete logical packets when later USB fragments
  arrive. Outbound logical data uses bounded/partial-safe writes.
- NCM bulk reads use an API-level-bounded request buffer. `Ntb16StreamDecoder`
  incrementally reconstructs complete NTB16 blocks across reads, accepts coalesced
  blocks and the USB short-packet pad, and passes only complete blocks to the NTB
  parser. Its bounded accumulation accommodates the full 16-bit NTB length and
  optional pad; it does not truncate a block to one API 22 read.
- No USB device or iPhone was used. API 22 wait/cancellation policy was exercised
  with fake backends; Robolectric in this repository does not provide SDK 22, so the
  Android Base64 behavior test runs at SDK 23. The compatibility decisions themselves
  are tested with API level 22 inputs.

**Remaining API and verification status**

The wired-path scan found no remaining unguarded API-above-22 calls in the audited
USBMUX, Lockdown, iAP2, NCM, VPN and AirPlay path. Newer USB overloads are confined
to the guarded compatibility helper; `java.time` and wired-path `java.util.Base64`
uses are removed. API 22 continues to be the app minimum. A `java.util.Base64`
reference remains in the separate ADB-key utility, outside the wired CarPlay path
and intentionally unchanged in this phase.

Added tests cover legacy USB queue sizing and fake blocking-wait timeout/close,
16 KiB chunking and partial writes, USBMUX fragmentation at 1, 16,383, 16,384,
16,385, 32,768, 65,535 and 65,536-byte frame sizes, fragmented/coalesced NTB16
blocks, Base64 vectors, and existing iAP2 protocol/control behavior. Additional
regressions exercise remote MFi client behavior, AirPlay video configuration,
ADB, cluster-song and iAP2 location tests. The selected unit-test run passed:
**92 tests, 0 failures, 0 errors, 0 skipped**.

The expanded `:common:testDebugUnitTest :shared:testDebugUnitTest` run was not
green: **1,414 tests ran, 43 failed** (42 in common settings/USB-filter test
classes and one unrelated DiLink 3 cluster-recovery test). These failures are
outside the changed wired-transport tests; they were not modified as part of this
phase. The targeted compatibility/regression selection above remains green.

`.\gradlew.bat :mobile:assembleDebug` completed successfully. The APK is a build
artifact only; `minSdk` remains 22 and `LegacyLaunchBuild.CONNECTIONS_ENABLED`
remains `false`. The existing phase diagnostics flags remain as configured; the
separate Phase 3B transport-test flag remains `false`.

Android Lint reports that the shared module as a whole is not clean
(**61 errors and 58 warnings**), with failures including Bluetooth permission
diagnostics and API-level findings outside this wired path (for example, the
separate ADB-key utility's `java.util.Base64`). The report contains no remaining
API-level findings in the audited USBMUX/Lockdown/iAP2/NCM/VPN/AirPlay path after
the API 22 fixes; the API 26 USB overloads are intentionally isolated in the
compatibility helper's newer-API branch.

**Not resolved by this phase:** legitimate MFi authentication credentials/provider,
end-to-end API 22 device validation, behavior of the target iPhone's USB
re-enumeration/configuration selection, and successful CarKit/iAP2/NCM/AirPlay
session establishment. No authentication workaround was added. The next authorized
step remains a separately reviewed Phase 3C.3B; this phase does not enable or start it.
A loadable public method is not permission to call it remotely. Reflection alone could
not prove whether `BtManagerService` delegates to the NForetek API; the subsequent
supplied-APK bytecode audit above establishes that relationship.

The operator chose **already-running only**, so a separate explicit service selection
binds with flags `0`, never `BIND_AUTO_CREATE`. Known components are allowlisted, and
export/enabled/permission checks are repeated before binding. False return, security error,
null Binder, crash/disconnect and binding death are reported. Once connected, a worker
reads standard Binder descriptor/class/interface metadata; no manually coded transaction
or vendor method is sent. This metadata request uses the platform Binder descriptor
mechanism, not a guessed vendor transaction number. Unbind occurs immediately after
metadata capture or failure, at the 5-second timeout, or on pause/destruction. Late callbacks
are ignored. A Binder metadata IPC may ignore thread interruption; timeout releases the
binding and discards late metadata, but cannot guarantee cancellation of vendor IPC.
Metadata uses a separate worker, so a stalled descriptor request cannot block inventory;
additional binds in that model are rejected while such an IPC remains outstanding.
Binding itself runs the vendor's `onBind`/`onUnbind` lifecycle; its internal side effects
cannot be guaranteed by DiPlay even though DiPlay sends no control command. A timeout
may mean stopped service or an intent/protocol mismatch, not proof of a permission failure.

Phase 3A, safe startup and Phase 3B.1 observation are preserved. No adapter enable,
pair/unpair, scan, SPP/RFCOMM, iAP2, authentication, full CarPlay or BYD integration is
enabled. Exact descriptors/permissions/loadable methods and usable transport remain
real-car evidence to collect; Phase 3C is not authorized.

### Phase 3B.6 — Native Geely CarPlay static audit

This audit covers the locally exported
`apple-discovery-com.neusoft.ecarx.settingwidget-base.apk`,
`apple-discovery-com.android.launcher3-base.apk`, and
`com.neusoft.optimus.wheeljack.setting-base.apk`, plus the narrowly scoped
AutoKit integration reference
`apple-discovery-cn.manstep.phonemirrorBox-base.apk`. APK manifests and DEX
bytecode were inspected on the PC; no APK code or native library was run. The
three Geely APKs contain no `lib/**` entries. The prior real-car inventory and
the Phase 3B.5b discovery procedure are described above. Conclusions below are
limited to those files and that inventory, not every possible firmware image.

#### Findings against the nine Phase 3B.6 questions

1. **What `CarPlaySwitch` controls.** In the setting-widget APK it is widget
   function ID `4`. Its click is dispatched to
   `SettingWidgetService.turnToCarPlay(boolean)`, which updates the widget's
   enabled/on state, writes Android `Settings.System["CarplayMode"]` (`2` for
   on, `3` for off), and asynchronously calls
   `AppleInterface.setDefaultMode(...)`. This is a native CarPlay mode/start-stop
   control path, not merely a cosmetic preference. The call path depends on
   the Apple JNI library; it is not evidence that the operation can complete on
   this vehicle. Do not click it as a test.
2. **Complete implementation or remnants.** The examined firmware artifacts
   show Java wrappers, USB-observer/service code, a mode widget and launcher
   status/UI hooks, but not a complete runnable native stack. The launcher
   `CarPlayReceiver` consumes `com.neusoft.ca.carplay.runningstate` and
   `com.neusoft.apple.device.disconnected` state signals to update launcher
   presentation; that is not a projection engine. The embedded
   `com.neusoft.appleservice.AppleService` class is not declared as a service in
   the supplied Settings manifest. A previous on-device package query returned
   `NameNotFoundException` for `com.neusoft.appleservice`. This is evidence of
   an incomplete/remnant path in the collected installation, not proof that
   every product firmware lacks an optional or differently packaged stack.
3. **Components not found in the collected scope.** The wrappers request
   `AppleCore_jni` and `ApplePrivate_jni` unconditionally through
   `System.loadLibrary`; neither library is packaged in the examined APKs or
   present in the prior named-library device search. The previous package query
   did not find the expected `com.neusoft.appleservice` package, and the
   embedded `AppleService` is not manifest-registered in the Settings APK.
   Consequently the JNI implementations (including the native method bodies),
   and a resolvable/registered Apple service host, are the concrete missing
   pieces. A working iAP2/MFi authentication engine and usable projection
   transport are also not established. These statements mean “not found or
   not established in the inspected scope,” not a claim that every system,
   vendor, 32-bit or product-variant location was exhaustively searched.
4. **Expected origin of the Apple libraries/service.** DEX identifies only
   the bare loader names `AppleCore_jni` and `ApplePrivate_jni`; it contains no
   alternate name or absolute path. Normal Android class-loader/linker
   resolution therefore expects matching native libraries in the loading
   package's native-library path or an applicable system linker path. The
   Java `AppleService`/`BootCompletedReceiver` references an external
   `com.neusoft.appleservice` package/service target, but no APK or exact
   library producer can be attributed from the inspected artifacts. The
   expected supplier is unresolved; the earlier 64-bit-only negative search
   is not exhaustive.
5. **Direct USB evidence.** Yes, there is material evidence for a wired
   device path: the embedded Apple service observes USB UEvents, derives a
   `/dev/` device path, queries `USBInfo.isCarPlaySupport()` and
   `switchDeviceMode()`, and the Apple-private wrapper can enable/disable
   `usbncm0` through a hidden network-management API. This supports an
   intended USB-host/NCM path, consistent with iPhone → USB → iAP2 → CarPlay.
   It does not demonstrate a successful iAP2 exchange, authentication or
   working projection on this unit.
6. **Native wireless CarPlay evidence.** No complete wireless path was found.
   `CARPLAY_REV_STS_IAP2_CMD`, Apple authentication hooks,
   `NCM_AUTO_UP_DOWN`, `isCarPlay_support` and
   `persist.neusoft.Apple.mode` are names/hooks, not a verified Bluetooth
   pairing-to-Wi-Fi session flow. The audited Apple code did not establish a
   Bonjour/AirPlay transport, wireless iAP2 session or CarPlay media session;
   no literal `MFi`/`mfi` string was found in the inspected DEX files.
   The already-proven vehicle hotspot and cross-device mDNS are useful network
   plumbing, but are DiPlay/vehicle-network evidence, not proof of OEM Apple
   wireless transport. No explicit model/region gate was identified in these
   APKs; values returned by native/configuration dependencies and other
   firmware-specific gates remain unknown.
   The adjacent `IPOD_START_LOCATION_INFO`,
   `IPOD_STOP_LOCATION_INFO`, `IPOD_START_VEHICLE_STATUS_UPDATE`,
   `IPOD_STOP_VEHICLE_STATUS_UPDATE` and `IPhoneBookCallBack` declarations
   are vehicle-status/phonebook API names, not evidence of an iPod/iPhone
   projection transport.
7. **Bridge to GEELY_BT/NForetek.** No functioning bridge is verified.
   Wheeljack contains a `CarPlayFeature.SetBluetoothIDs(byte[])` helper, but
   the DEX search found no caller. It also contains a conditional
   `BluetoothService$9.onSppAppleIapAuthenticationRequest(String)` callback
   that prepares a fixed seven-byte reply and would call
   `INfCommandSpp.reqSppSendData()` if an SPP interface were present. The
   standard Bluetooth-service lifecycle does not bind the SPP service, and
   the installed NForetek SPP implementation's methods are no-op/false; its
   lifecycle can affect the shared backend. This is a dormant hook, not a
   viable Apple byte transport. The vehicle's real Bluetooth stack is
   NForetek `GEELY_BT`; Android `CAR_BT` is separate and is not evidence of an
   Apple bridge. Do not bind or exercise SPP.
8. **Safe next head-unit test.** Run the existing manual Phase 3B.5b
   read-only Apple implementation discovery/inventory while parked, then stop
   and inspect only its exported APKs, package metadata, native-library
   directories and both 32/64-bit roots on the PC. This is the smallest safe
   check for a renamed/variant package or library missed by the prior
   name-specific, 64-bit search. Do not launch the Apple dialog, click the
   widget, bind/start services, broadcast, load libraries, use SPP, alter USB
   roles, or connect/authenticate an iPhone. See the matching procedure in
   `TESTING.md`.
9. **Shortest realistic dongle-free route.** First use that inventory to
   locate the complete, compatible OEM Apple native/service implementation
   from a legitimate matching firmware package, if it exists. Do not activate
   it until a separate, explicitly authorized hardware-test phase. If it is
   absent, DiPlay needs its own direct iPhone transport: direct USB host is a
   possible development path, while the target requires direct Bluetooth
   iAP2/control plus a Wi-Fi CarPlay network/media session and a legitimate
   authentication/certification path. Existing hotspot/mDNS evidence reduces
   uncertainty only in network setup. The target is a direct iPhone-to-head-unit
   connection: no Carlinkit, AutoKit or external projection dongle is required
   or part of the architecture.

#### Steering-key and AutoKit boundaries

The exact `com.neusoft.HardKeyAidlInterface` name was not found in the three
Geely DEX files, so its owning package, methods and callbacks remain
unidentified. Wheeljack does contain the distinct ECARX SDK
`com.ecarx.sdk.input.hardkey.IHardKeyAPI` declaration
(`registerCallback`, `requestInterceptHardKeys`, `unregisterCallback`) and
`IHardKeyCallback` short-click/long-press methods. The inspected Wheeljack
DEX declares the API accessor but contains no verified accessor call or
callback registration; it is not evidence that this is the requested Neusoft
AIDL. AutoKit's visible main DEX is a `StubApp` loader; its decoded
`HWTouch.dex` has touch down/move/up helpers using reflected
`InputManager.injectInputEvent`. The packed AutoKit payload prevents a
confident whole-app negative search for the exact AIDL, and the touch helper
does not identify its owner. No proprietary adapter protocol was investigated.

**Static-audit boundary:** no CarPlay setting/flag was changed; no dialog was
opened; no vendor service was bound or started; no broadcast was sent; no
native library was loaded; no USB role or Bluetooth state was changed; no
iPhone was connected/authenticated; and no Phase 3C work was performed.
`vendor-apks/` remains local and gitignored.

### Phase 3B.7 — Complete native Geely CarPlay stack discovery

#### Scope and evidence boundary

This phase rechecked the six locally available APK DEX files and manifests:
Launcher3, settingwidget, Wheeljack Settings, AutoKit, NForetek Bluetooth API
and the Neusoft BT phone backend. The three Geely APKs contain no native `.so`
entries. Static tools inspected APK contents only; no APK or library was
executed. `adb devices -l` returned no attached device during this phase, so
there was no new search of raw `/system`, `/vendor`, framework JARs or installed
package `nativeLibraryDir` locations. The repository has no second firmware
dump/APK set. The earlier device inventory and negative exact-package query
remain evidence, but the earlier named-library search was not a recursive,
all-ABI partition audit.

The existing manual discovery collector covers installed-package metadata,
their APKs and selected library roots; it does not recursively scan all raw
system/vendor APKs and JARs for DEX strings, nor inspect all requested
framework locations. Accordingly, absence below means "not found in the
identified evidence scope", not a claim that every partition of this firmware
has been exhausted. No new ordinary files were collected in this phase; there
are no new source-path/destination/size/hash records to report.

#### Missing-component classification

| Item | Classification | Evidence and limit |
| --- | --- | --- |
| `AppleCore_jni` | REFERENCED BUT MISSING | `AppleInterface.<clinit>()` unconditionally calls `System.loadLibrary("AppleCore_jni")` in both Wheeljack Settings and settingwidget. No matching `.so` is in either APK; it was not found by the prior named-library device search. Raw system/vendor paths were not re-scanned in this phase. |
| `ApplePrivate_jni` | REFERENCED BUT MISSING | `ApplePrivate.<clinit>()` unconditionally calls `System.loadLibrary("ApplePrivate_jni")` in both APKs. Same scope limit as above. |
| `AppleInterface` | PRESENT WRAPPER ONLY | Java singleton and native declarations exist in both APK DEX files. `setDefaultMode(int)` forwards to `appleCore_native_setDefaultMode(int)`; no JNI implementation was found. |
| `ApplePrivate` | PRESENT WRAPPER ONLY | Java singleton, USB/device-mode helpers and native declarations exist in both APK DEX files; the JNI implementations are not present in those archives. Hidden network-management calls are also required by the USB/NCM path. |
| `AppleService` | DORMANT / UNREGISTERED | Java service implementation and its initialization logic are embedded in both DEX files. Neither inspected Geely APK manifest registers it or the embedded Apple boot receiver. The earlier package inventory also returned `NameNotFoundException` for package `com.neusoft.appleservice`. |
| `com.neusoft.appleservice` | REFERENCED BUT MISSING | Embedded constants/boot code refer to it as an intent action and explicit package target; the earlier installed-package query did not resolve it. This is not a present runnable service package in that inventory. |
| `usbncm0` | UNKNOWN | The wrappers contain code to configure the NCM interface; the current kernel interface/device state and successful network setup were not observed. |
| Authentication / MFi / iAP / iAP2 engine | REFERENCED BUT MISSING | Java service callbacks and Apple/native hooks indicate intended authentication/device handling, but no complete iAP2 handshake/engine, MFi authentication payload or working authentication component was established in the inspected APKs. |
| Launcher CarPlay state broadcasts | PRESENT AND IMPLEMENTED | Launcher3 declares `CarPlayReceiver` and handles `com.neusoft.ca.carplay.runningstate` and `com.neusoft.apple.device.disconnected` for launcher state/presentation. The disconnected action is also embedded in the Apple service code in settingwidget and Wheeljack. These are state/UI signals, not a projection or transport implementation. |
| `Settings.System["CarplayMode"]` | PRESENT AND IMPLEMENTED | Setting-widget and Wheeljack DEX read/write the key and observe its URI. The key is connected to Java UI state and explicit native-wrapper call paths, but a raw setting write by itself is not proven to start a complete Apple engine. |

The classes being duplicated across two APKs does not supply either missing JNI
library. `AppleService` code attempts to initialize both wrappers, configure
mode and subscribe to authentication/death/USB events, but the service is
unregistered in the inspected manifests and its native dependencies remain
unresolved. Thus a complete runnable native CarPlay engine is not present in
the collected APK set.

#### JNI load path and callers

Both APK copies use bare `System.loadLibrary` names from the respective
`AppleInterface` and `ApplePrivate` class initializers. No alternate library
name, absolute path, custom `ClassLoader`, `DexClassLoader`, explicit
`nativeLibraryDir` load, or Java-side cross-process/preload mechanism was found.
Under normal Android resolution, each call uses the class loader for the APK
that defines the class and Android's applicable linker search paths. Static
evidence does not identify a separate supplier APK, process or linker
configuration; therefore the original expected package/path for these files
remains unknown. A bare library name alone does not prove the files had to be
stored in `/system/lib*`.

The two DEX copies declare the same native method names. `AppleInterface`
declares `appleCore_native_appledevice_connected`,
`appleCore_native_attachServer`, `appleCore_native_callStateUpdate`,
`appleCore_native_detachServer`, `appleCore_native_exit`,
`appleCore_native_favoriteListUpdate`, `appleCore_native_getAppName`,
`appleCore_native_getArtworkInfo`, `appleCore_native_getConfig`,
`appleCore_native_getDeviceName`, `appleCore_native_getFavoriteListCount`,
`appleCore_native_getNowPlayingInfo`, `appleCore_native_getPlayStatus`,
`appleCore_native_getPlayTime`, `appleCore_native_getPlayingList`,
`appleCore_native_getPowerInfo`, `appleCore_native_getRecentListCount`,
`appleCore_native_getRepeatMode`, `appleCore_native_getShuffleMode`,
`appleCore_native_getUSBInfo`, `appleCore_native_init`,
`appleCore_native_playAllSong`, `appleCore_native_playCollection`,
`appleCore_native_playUidList`, `appleCore_native_powerSourceUpdate`,
`appleCore_native_recentListUpdate`, `appleCore_native_requestAppLaunch`,
`appleCore_native_sendLocationInfo`,
`appleCore_native_sendPlaybackRemoteCmd`,
`appleCore_native_sendVehicleStatus`,
`appleCore_native_setDefaultMode`,
`appleCore_native_setNowPlayingInformation`,
`appleCore_native_startMediaLibraryUpdate` and
`appleCore_native_stopMediaLibraryUpdate`.
`ApplePrivate` declares `applePrivate_native_appledevice_connected`,
`applePrivate_native_attachServer`, `applePrivate_native_close`,
`applePrivate_native_detachServer`, `applePrivate_native_exit`,
`applePrivate_native_getUSBInfo`, `applePrivate_native_init`,
`applePrivate_native_open`, `applePrivate_native_setMode`,
`applePrivate_native_startAudio`, `applePrivate_native_stopAudio`,
`applePrivate_native_switch_device_mode` and
`applePrivate_native_switch_host_mode`. These declarations cover media,
device, audio, vehicle and USB/NCM operations; they are JNI interfaces, not
implementations. Their native bodies remain missing from the inspected APKs.

The wrapper method `AppleInterface.setDefaultMode(int)` is Java, returns `int`,
and directly delegates to the native `appleCore_native_setDefaultMode(int)`.
The native method body is unavailable. The app call sites found are
`SettingWidgetService$4.run()` and `CarPlayDialog$4.run()`; initialization and
singleton access also occur in `SettingWidgetService.turnToCarPlay`,
`CarPlayDialog.onCreate`, the embedded `AppleService.onCreate` and the embedded
boot receiver. The latter service/receiver are not registered in the inspected
manifests. No named Java mode constants define the native parameter's meaning.
Callers use `0`/`1` or derive a bit-flipped value from current UI state, which
strongly suggests a default-mode/enable selection; whether native code treats
it as CarPlay enablement, accessory/projection mode or another setting is
UNKNOWN. There is no evidence that it changes USB role or launcher mode.

`ApplePrivate` is initialized and called by the embedded Apple service code;
no independent, manifest-reachable ApplePrivate service client was established.
`AppleInterface` calls are in the widget toggle, Settings CarPlay dialog and
embedded service/boot initialization paths. The unguarded class initializers
mean these are not safe availability probes.

#### `CarplayMode` readers and writers

| APK / package | Class and method | Access | Values / observed effect |
| --- | --- | --- | --- |
| settingwidget / `com.neusoft.ecarx.settingwidget` | `SettingWidgetService.turnToCarPlay(boolean)` | Write | Writes `2` for the switch's on path and `3` for off, updates widget state and explicitly initializes/accesses `AppleInterface`; it schedules the `SettingWidgetService$4` runnable which calls `setDefaultMode`. |
| settingwidget | `SettingWidgetService$2.handleMessage` | Write | Writes the switch's corresponding `2`/`3` state in its two handler branches. |
| settingwidget | `SettingWidgetService.getCarPlayState()` | Read | Reads the setting (default `0`) to derive widget state. |
| settingwidget | `SettingWidgetService$3.onChange()` | Read | Reads the setting after its URI changes and updates widget state; it does not itself call the native setter. |
| settingwidget | `Utils.<clinit>()` | Observer URI | Creates the `Settings.System` URI for `CarplayMode`; this is registration metadata, not a value read/write. |
| Wheeljack / `com.neusoft.optimus.wheeljack.setting` | `ConnectHomeFragment$DataObserver` constructor and `onChange()` | Observe, read | Registers for the setting URI; `onChange()` reads with default `0` and updates the connect-screen mode state. |
| Wheeljack | `CarPlayDialog.initCarMode()` | Read | Reads with default `0` and initializes the CarPlay/CarLife radio-button UI. |
| Wheeljack | `CarPlayDialog$CarModeObserver` constructor; `CarPlayDialog$1.handleMessage()` | Observe, read | The constructor registers the setting URI; the handler re-reads the value to refresh dialog mode/UI. |
| Wheeljack | `CarPlayDialog$3.onClick()` | Write | The two mode-choice branches write `2` or `3`; the dialog presents CarPlay and CarLife choices. These are mode values in this dialog, not a universal Boolean interpretation. |

The stock code does not support classifying `CarplayMode=2` as *only* a
cosmetic UI value: the widget's explicit user-action method couples its write
to Apple wrapper initialization and a native `setDefaultMode` call. Conversely,
the setting itself is not proven to be a global activation flag: its observers
refresh UI state, the dialog uses `2`/`3` as CarPlay/CarLife choices, and no
separate system component that initializes the Apple engine solely upon an
arbitrary setting write was found. The safest exact description is a shared
mode/state setting used by UI code that separately invokes native control code.

#### Cross-reference, transport and gating findings

| Reference family | Local occurrence and interpretation |
| --- | --- |
| `CarPlayReceiver`, `com.neusoft.ca.carplay.runningstate`, `com.neusoft.apple.device.disconnected` | `CarPlayReceiver` and both action filters are in Launcher3. `com.neusoft.apple.device.disconnected` also appears in the embedded Apple service code in settingwidget and Wheeljack. The actions signal state/disconnection; they do not implement transport. |
| `CarPlaySwitch`, `CarplayMode` | Setting-widget function ID 4 to `turnToCarPlay`; Wheeljack dialog and observers also read/write the setting as described above. |
| `AppleInterface`, `ApplePrivate`, `AppleService`, `com.neusoft.appleservice` | Wrapper and embedded service classes are in both settingwidget and Wheeljack DEX; the expected installed service package was absent in the prior package inventory and the inspected manifests do not register the embedded service/boot receiver. |
| `AppleCore_jni`, `ApplePrivate_jni` | Unconditional bare-name load calls in both APK copies; no corresponding library in the APK archives or prior named-library search. |
| `usbncm0`, `NCM_AUTO_UP_DOWN`, USB/UEvent/`USBInfo`/device-mode | USB/NCM implementation hooks in Apple wrapper/service code. This supports an intended wired path, not proof of a complete host, iAP2, authentication or projection session. |
| `MFi`, `iAP`, `iAP2`, authentication | Authentication-related callback/native hooks and an iAP2 command-name hook exist, but no complete Java implementation, legitimate MFi authentication payload or working iAP2 exchange was identified. No MFi literal/engine was found in the inspected DEX. |
| `SetBluetoothIDs`, `CarPlaySetBluetoothIDs` | `CarPlayFeature.SetBluetoothIDs(byte[])` and its `"CarPlaySetBluetoothIDs length err:"` diagnostic are in settingwidget and Wheeljack; no caller was found. No effective Apple-to-NForetek bridge was established. |
| `iphone`, `ipod`, `bonjour`, `airplay`, projection | iPhone/iPod-named status/vehicle-information declarations (including `IPOD_*_LOCATION_INFO`, `IPOD_*_VEHICLE_STATUS_UPDATE` and `IPhoneBookCallBack`) do not establish projection. No complete Bonjour/AirPlay session path or native projection engine was found in the inspected Geely APKs. |
| `com.neusoft.HardKeyAidlInterface` | Exact interface name not found in the six local APK DEX files. Wheeljack contains a distinct abstract Java API: `IeCarXAPI.getHardKeyApi()` returns `IHardKeyAPI`; that interface declares `registerCallback(IHardKeyCallback)`, `requestInterceptHardKeys(KeyCode[]) -> boolean`, and `unregisterCallback(IHardKeyCallback)`. The callback has `onKeyLongPress(KeyCode)` and `onKeyShortClick(KeyCode)`, both returning boolean; the included `KeyCode` enum contains only `MEDIA_NEXT` and `MEDIA_PREVIOUS`. No accessor invocation, callback implementation/registration or key mapping was found. These classes do not identify the owner or Binder descriptor of the separately requested Neusoft interface. |

The embedded USB/UEvent and NCM helpers are direct evidence for an intended
USB accessory path: iPhone → USB → Apple/iAP2 handling → CarPlay. The iAP2,
MFi authentication and full session portions are not proven. No completed
wireless chain was found: there is no verified NForetek/`GEELY_BT` iAP2 control
bridge, no stock Bluetooth-to-hotspot session orchestration and no native
Bonjour/AirPlay media path in the inspected APKs. The already-proven vehicle
hotspot and cross-device mDNS are network facts, not an OEM CarPlay transport.
The conditional SPP authentication callback remains a dormant hook; do not use
`NfServiceSpp`.

No explicit region/country/model/E01/VX11/product-code gate was found in the
inspected DEX. The mode dialog reads `persist.neusoft.Apple.mode` (including a
`CarLife` comparison), and the wrappers expose native configuration and
`USBInfo.isCarPlaySupport()` checks. These are leads for mode/capability
gating, not proof of a region or vehicle gate; their native/config values could
not be evaluated. Missing package/JNI payload is the strongest evidenced
blocker in the collected scope. Hardware and licensing/authentication status
remain unresolved.

No alternate Geely/ECARX E01 firmware is present in the repository. An offline
comparison would require a verified E01, preferably VX11/Okavango-family,
firmware build known to include native wired CarPlay, with its build/model/
region metadata and readable `/system` and `/vendor` app, priv-app, framework
JAR and native-library contents. No external firmware was downloaded.

#### Direct answers

1. **Complete native implementation? — STRONG EVIDENCE: no complete runnable
   implementation is established in the supplied installation artifacts.**
   Wrappers, mode UI, launcher state hooks and USB/NCM service code exist.
2. **What is missing? — PROVEN within the inspected APK scope:** both JNI
   implementations are absent from the APKs; the expected installed
   `com.neusoft.appleservice` package was absent in the earlier package query;
   the embedded service is unregistered in inspected manifests. **UNKNOWN:**
   whether a renamed/optional native payload exists elsewhere on this firmware.
   No complete iAP2/MFi/authentication engine or CarPlay media/session
   transport was found.
3. **Is `CarplayMode=2` an activation flag? — STRONG EVIDENCE:** it is a mode/
   state input, not a standalone global activation flag. The stock switch's
   action separately writes it and invokes native wrapper control; dialog and
   observers also use it for UI mode state.
4. **What does `setDefaultMode()` do? — PROVEN:** Java wrapper forwarding to
   `appleCore_native_setDefaultMode(int)`, returning its result. **UNKNOWN:**
   exact native semantics; callers suggest default-mode/enable selection, not
   USB-role or launcher-mode control.
5. **Where should the JNI libraries come from? — PROVEN:** the defining APK's
   normal class-loader/linker resolution for the bare names.
   **UNKNOWN:** their original supplier APK, system path or preload process;
   no path or producer is named by DEX.
6. **Legitimate iAP2/MFi authentication present? — UNKNOWN / not established.**
   Hooks and callback names are not proof of an authentication implementation;
   no complete engine or successful exchange is in evidence.
7. **Direct USB iPhone transport? — STRONG EVIDENCE for intended USB/NCM
   plumbing; UNKNOWN for a complete authenticated CarPlay session.**
8. **Native wireless transport? — UNKNOWN, with no complete stock chain found
   in the inspected APKs.** Do not treat hotspot/mDNS alone as CarPlay.
9. **Functional bridge to `GEELY_BT`? — STRONG EVIDENCE: none found.** The
   SPP/authentication callback is conditional and the prior NForetek analysis
   showed that SPP path is dead/no-op.
10. **Could another E01 variant contain the payload? — PLAUSIBLE.** No alternate
    image exists locally, so this has not been compared.
11. **Single safest next step —** run a complete read-only inventory of the
    current head unit's ordinary readable system/vendor APK, framework/JAR and
    native-library files, recording source path, export destination, size and
    SHA-256. This resolves whether a renamed/optional payload is already on
    this unit without executing or activating it. If that inventory is
    negative, the strongest next lead is an offline comparison against a
    verified wired-CarPlay E01/VX11/Okavango firmware variant.

No mode/settings/feature flag was changed, no service was started or bound, no
broadcast was sent, no native library was loaded, no USB role was changed and
no iPhone was connected or authenticated. Phase 3C was not started.

### Previous Phase 3B.1 inventory

Real Okavango validation of `0.2.12-api22-phase3b-safe-startup` succeeded: the app
launches without crashing. With vehicle Bluetooth ON, the vehicle UI reports `GEELY_BT`,
while Android reports service/adapter present, adapter disabled, name `CAR_BT`, address
`00:00:00:00:00:00` and zero bonded devices. This is evidence of a separate/nonstandard
integration, not proof of its mechanism or of standard RFCOMM availability.

Phase 3B.1 label was `0.2.12-api22-phase3b1-vendor-discovery`. Safe startup and Phase 3A are
unchanged; all transport/full-session/vehicle gates stay disabled. A separate manual
Settings action inventories public system-service objects (without calling adapters),
public ActivityManager running-service metadata, visible PackageManager packages and
exported services/receivers/providers. Matching names use `bluetooth`, `bt`, `geely`,
`ecarx`, `smartplatform`, `mcu`, `car`, `phone`, `handsfree`, `hfp`. Broad terms can
produce unrelated matches; component presence is not proof of Bluetooth transport.
Package labels/application classes, APK/native-library paths, component processes,
permissions and provider authorities are recorded. No component is bound or invoked.

Receiver actions are parsed from readable APK manifest metadata through public
PackageManager/AssetManager APIs. Standard Bluetooth plus matching declared receiver
actions are observed for 30 seconds after enumeration (max 128 actions/200 events).
Listening continues while the activity is paused so the operator can use the vehicle UI;
timeout or activity destruction unregisters it. There is no automatic restart.
Broadcast reports contain action, relative timestamp and extra *keys*, with values omitted
except standard adapter state integers. A broadcast's sender cannot be established by
these API 22 callbacks; absence of events does not prove the vendor uses no broadcasts.

Public APIs cannot list all Binder services or live Android system properties.
No private ServiceManager/SystemProperties reflection, shell/getprop process, ADB,
vendor command or JNI probe is used. Ordinary reads of `/system/build.prop`,
`/vendor/build.prop` and `/system/vendor/build.prop` select only allowlisted device/build
metadata and vendor/Bluetooth name/version/platform/chip/type/support/enabled keys.
Unreadable files and permission/vendor exceptions are reported. Serial numbers, keys,
credentials and arbitrary property values are excluded. Properties are file snapshots,
not current Bluetooth state. Reports cap at 4,000 lines and mark truncation.
Newer Android package visibility and running-service restrictions are explicitly noted;
no broad package-visibility privilege was added.

Repository investigation: `BydHudBridge` binds the explicit
`com.ts.car.someip.SomeIpServerService` and uses Binder transactions/callbacks for HUD
navigation. `BydVehicleFields`, `BydBattery` and `BydClusterSong` use authorized local ADB
and `service call autoservice`; battery reads `ro.car.protocol` with getprop over that
shell. These are BYD-specific reference patterns, not a Geely Bluetooth abstraction,
and none are used by this inventory. `WheelKeyService` references the BYD Bluetooth-music
icon and call audio mode, not a vehicle Bluetooth proxy. Existing native integrations are
`local_hotspot_radio` and `xcertplay_i2c`, not Bluetooth/MCU discovery. No Geely/ECarX/
SmartPlatform transport/proxy or Bluetooth JNI library was found in project sources.
Live service/component identities require the real-car report; the mechanism remains unknown.

### Previous safe-startup milestone (hardware confirmed)

That build label was `0.2.12-api22-phase3b-safe-startup`. Only explicit Refresh enables
read-only Android Bluetooth diagnostics. Scan/RFCOMM/iAP2 buttons are hidden and their
activity actions gated off pending confirmed launch on the real Okavango.
`PHASE3B_TRANSPORT_TESTS_ENABLED` remains false. The earlier device-test build supported
manual discovery and pre-auth transport tests; that is not this build's milestone. The connection
and vehicle-integration gates remain false. No full controller, MFi client, identity,
AirPlay media, USB projection, hotspot transition, Wi-Fi Direct or LocalOnlyHotspot is started.

### Phase 3B launch-regression fix

Compared Phase 3B commit `621a0f5` against the hardware-good Phase 3A commit `3816f25`.
Phase 3B had added diagnostics construction in every `DiPlayActivity.onResume`; its
constructor registered Bluetooth receivers and scheduled immediate, repeated adapter,
name/address and bonded-device/UUID reads. These automatic vendor-stack interactions
were absent from Phase 3A. The exact head-unit exception is not known without crash logcat.
The leading suspect is eager Bluetooth initialization/receiver registration or a vendor
runtime/linkage failure; a generic API 23 adapter-lookup mistake is not proven because
the shared lookup already guarded that API.

The corrected build leaves Phase 3B uninitialized through home/Settings rendering,
resume, report export and pause unless the operator presses a Phase 3B action. No
periodic Bluetooth sampling or startup receiver registration remains. Refresh performs
a single read. Receivers exist only for manual discovery and are removed on completion,
timeout, startup failure, callback failure, test transition or pause. Report rendering
uses stored socket state rather than calling vendor socket APIs. Diagnostic operations
contain ordinary exceptions and `LinkageError` and show failures in the report/log;
fatal VM errors are not suppressed. RFCOMM reader linkage failures become observable
I/O failures instead of uncaught reader-thread errors.

Application/manifest startup, `onCreate`, authentication/vehicle gates and all Phase 3A
network initialization remain unchanged. The Phase 3B activity-result launcher registers
a callback only; Bluetooth permission checks occur solely after an explicit action.
An authorized permission-result action may resume after the permission dialog, but an
ordinary resume cannot create Bluetooth diagnostics. The Okavango launch must be retested.

| Startup surface | Bluetooth execution before / after correction |
| --- | --- |
| Application/providers | No custom Application or Bluetooth initializer in the merged mobile manifest. Report FileProvider and AndroidX startup providers do not initialize this diagnostic model. No Bluetooth changes here. |
| Activity fields / `onCreate` | Nullable diagnostic reference, cached text, permission-result callback registration and UI creation only; no manager/adapter/permissions query. Full bootstrap and vehicle paths remain gated off. |
| `onStart` | Only the full-connection-gated overlay hook; no Phase 3B work before or after. |
| `onResume` | Previously constructed Phase 3B diagnostics unconditionally. Now only consumes an explicitly authorized pending permission action; ordinary resume does no Bluetooth work. Phase 3A starts exactly as before. |
| Settings construction | Cached text and Refresh handler only; no diagnostic creation or permission query. Scan/RFCOMM controls are withheld and action-dispatch guarded. |
| Diagnostic constructor | Previously registered broadcasts and scheduled immediate/repeated adapter, state, name/address, bonded-device and cached-UUID reads. Now inert: no lookup, receiver registration, sampling, socket or iAP2 engine. |
| Manual Refresh | The only active entry: guarded service/adapter/state/name/address/bonded/UUID reads and explicit error reporting. No enabling, discovery, transport or vendor integration. |
| Report / pause cleanup | Cached reports and stored socket state; cleanup does not look up Bluetooth unless a scan was explicitly owned. No scan can be started by this safe build. |

Real hardware also reports that only the Geely vehicle Settings UI can operate Bluetooth;
normal Android Settings cannot enable it. Android service availability, adapter presence,
enabled state and successful bonded-device enumeration are therefore separate diagnostic
observations, not the vehicle's Bluetooth state. Manual Refresh uses BluetoothManager
only and never falls back to `getDefaultAdapter`; no adapter enabling is attempted.
An absent/disabled/restricted Android stack may coexist with working vendor/MCU Bluetooth.
The operator reports the vehicle name `GEELY_BT`, not a name detected by DiPlay.
The report flags that possible mismatch but labels current vehicle state unobservable;
the operator must verify it in the Geely UI. No vendor API is queried.
Scan support is explicitly **unknown/not tested**: diagnosing it by discovery would violate
the current launch-only milestone. A missing service/adapter, failed state/name/address
read, null bonded-device result or vendor exception is reported as unavailable/failed,
not a successful empty list or evidence that the vehicle radio is off.

Repository inspection found no Geely/SmartPlatform Bluetooth abstraction or vendor RFCOMM
transport. The BYD vehicle/settings/navigation code is not a Bluetooth adapter replacement.
`WheelKeyService` mentions a BYD Bluetooth-music icon and call audio mode, not Bluetooth
service access. The standard controller's profile/hidden connection-state lookup and
secure-address helper likewise do not expose the Geely service. All BYD integration stays
disabled. No connected head unit was available to inspect its live Binder/MCU services.
Understanding that integration, if necessary, is future investigation after safe launch,
not permission to enable BYD behavior or start Phase 3C.

### Bluetooth/iAP2 audit and API boundaries

| Surface | Finding / treatment |
| --- | --- |
| Controller, phone chooser, local address helper | Typed `getSystemService(Class)` requires API 23. Shared Bluetooth lookup uses named `BLUETOOTH_SERVICE` on API 22, public adapter fallback, and typed lookup on API 23+. |
| Permissions | API 22 uses install-time `BLUETOOTH` / `BLUETOOTH_ADMIN`, already declared through API 30. No API 23 runtime permission call or API 31 CONNECT/SCAN requirement is applied on API 22. |
| Newer discovery | API 23-30 manual scanning requests fine location; API 31+ requests CONNECT and SCAN. CONNECT-only RFCOMM does not read/cancel discovery without SCAN. The manifest adds SCAN with `neverForLocation`. |
| Host wireless/location checks | The API 22 permission branch is empty; fine-location checks guard API 23. This does not enable the host/controller. |
| Bonded devices, cached SDP UUIDs, socket I/O | Public APIs available before API 22. No hidden channel-number fallback, insecure RFCOMM fallback, adapter enable, programmatic pairing or automatic SDP request is used. Cached UUID absence is reported as unknown, not proof the service is absent. |
| Controller automatic device selection | Existing hidden `BluetoothDevice.isConnected` remains in the disabled full controller. Hardware mode never uses it; the operator explicitly selects a currently bonded device. |
| UUID | Secure `createRfcommSocketToServiceRecord` uses the existing `00000000-deca-fade-deca-deafdecacafe` service UUID, now shared with the controller. |
| `BluetoothRfcommDuplexStream` | Existing bounded reader, timeout facade, write/flush and socket-close APIs are API 22-compatible. Reused without a private API or new Android dependency. |
| `Iap2WirelessControlClient` | Pure protocol code, but its full sequence performs identification then immediately `mfi.run`, subscriptions, Wi-Fi configuration and CarPlay startup. Not invoked. |
| Link/CSM/session framing | Pure JVM code. Diagnostic probe drives only `Iap2LinkEngine` and `Iap2CsmFramer`, with no CSM send or artwork response path. |
| Out-of-scope newer dependencies | Full controller also uses API 23 USB service lookup; location reporting uses API 26 `java.time`. Neither is reachable in this mode. Further full-session compatibility work is not authorized by this phase. |

The prior transport-test implementation (unreachable from this safe-startup build) uses a
12-second RFCOMM connect watchdog to close the socket; successful connection is followed by
at most 10 seconds of iAP2 detection-marker/SYN/ACK exchange. The first CSM frame stops the test.
`StartIdentification` (`0x1d00`) is reported without sending `IdentificationInformation`
(`0x1d01`). If a phone requests authentication directly, `RequestAuthenticationCertificate`
(`0xaa00`) or `RequestAuthenticationChallenge` (`0xaa02`) is reported with no certificate
(`0xaa01`) or challenge response (`0xaa03`). Raw payloads are not logged. The normal client
would call MFi after identification acceptance; that boundary is not crossed here.

RFCOMM connection alone, marker transmission alone, link readiness, first control frame and
authentication are distinct milestones. Real RFCOMM/iAP2 success with the Okavango and iPhone
is still required. Identification/authentication or Phase 3C must not begin without approval.

## BYD HUD and car hotspot

See [BYD navigation](BYD_NAVIGATION.md) for the exact verified firmware and lifecycle limits. Car hotspot now starts CarPlay on the development car using scoped IPv6. The phone must join the configured car hotspot. Neither result guarantees support on every firmware.

## Known limitations

- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and a lower resolution, then attach a report.
- A contributor reported periodic wireless CarPlay stutter on a 2023 Han with GCC DiLink 3 when the car's Wi-Fi client was disconnected: scans every 10 s took the radio off the CarPlay channel for 3-6 s. On Android 10, with network ADB already authorized and the framework transaction available, DiPlay pauses connectivity scans for hotspot/P2P sessions. Same LAN is excluded so station reconnect and roaming remain available. Controller leases share one serial worker; scans restore after the last lease closes. A durable marker precedes suppression, failed restores retry every 30 s while the app runs, and app startup recovers an interrupted restore. A force-stop can delay restoration until the app is opened again. Missing approval or transaction support leaves scans alone. The updated in-app lifecycle needs a parked vehicle retest; without ADB, joining the car to a hotspot also slowed scans in the contributor's test.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The capability flag is diagnostic, not proof of group-owner support.
- Automatic startup depends on the car's firmware and startup permissions.
- USB requires a data port and correct host/device-role behavior.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.

Reports record requested and actual frequencies, station association state, fallback failures and remembered-configuration events. Wi-Fi credentials and protocol payloads are excluded. A successful hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).

### Phase 3C.1 — QDrive iPhone transport static audit

**Scope:** static PC analysis of `vendor-apks/QDrive_Global/QDrive_Global.apk` and its
nine ARM libraries. `btphoneNF.apk` and `Bluetooth-GocBtAPI.apk` were inspected only
to resolve the ECARX/NForetek SPP service ownership. No vendor service was started or
bound, no library was loaded, no Bluetooth or USB operation was issued, and no iPhone
was connected or authenticated.

The QDrive package is `com.neusoft.ssp.ces.c4.car.assistant`, version `1.5.0`
(version code `1005`, min SDK 18, target SDK 27, `armeabi-v7a`). The conclusions below
use **PROVEN** for direct DEX/manifest/ELF evidence, **STRONG EVIDENCE** for multiple
matching static indicators without a complete execution trace, **PLAUSIBLE** for an
interpretation, and **UNKNOWN** where static evidence does not resolve the behavior.

#### Java transport paths

**Direct USB — PROVEN static QDrive iPhone transport path; runtime success was not
tested, and this is not CarPlay.**

The relevant Java chain is:

```text
MainSDKService activation/startup path (when IS_ACTIVATE == 0)
  -> PhoneMux.start(context)
  -> UsbManager device polling and USB permission request
  -> PhoneMux.registDevice(device)
  -> UsbManager.openDevice(device)
  -> UsbDeviceConnection.getFileDescriptor() and device path
  -> JNISSPAirPlayUSBSDK.JNI_SSP_AddUsbDevice(fd, devicePath)
  -> libSSPAirPlayUSB.so
  -> libusbserver.so / libusb / usbmuxd / Lockdown
```

`PhoneMux` starts the USB server using `JNI_SSP_StartUsbServer`, polls
`UsbManager.getDeviceList()`, and filters for Apple vendor ID `0x05ac` and the
strict product-ID interval `0x1291`–`0x12ae`. It requests Android USB-host permission
using `com.usbscreen.androidusbmuxd.USB_PERMISSION`; its receiver checks the permission
result and revalidates the Apple device before opening it. This is Android USB-host
permission, distinct from iPhone Trust approval.

`libusbserver.so` contains executable usbmuxd/Lockdown code and strings for
`ReadPairRecord`, `SavePairRecord`, `PairRecord`, `ValidatePair`, `StartSession`,
`com.apple.mobile.lockdown`, and errors for missing/invalid pairing records. It also
contains explicit messages for waiting for the user to trust the computer and for
the user having trusted it. **STRONG EVIDENCE:** first-time pairing expects iOS
Lockdown trust approval; the exact UI and service opened after `StartSession` were
not executed or fully resolved.

**Bluetooth/SPP — interfaces and native code exist, but no usable ECARX byte path is
established.**

QDrive's DEX contains both:

- Legacy `com.ecarx.xui.adaptapi.bt.spp.ISpp`, with readiness/state, connect/disconnect,
  callback registration, connected-device-list, and `reqSppSendData(String, byte[])`
  methods. Its callback includes raw-byte receive/send notifications and
  `onSppAppleIapAuthenticationRequest`.
- Binder `com.ecarx.xui.adaptapi.bt1.spp.ISpp` and `ISppCallback`, with descriptors
  matching those interface names. The Binder API carries byte arrays through
  `reqSppSendData` and `onSppDataReceived`; the callback also declares the iAP
  authentication-request notification.

The client-side `com.ecarx.xui.adaptapi.bt.BtImpl` creates phone/settings/PBAP/A2DP/
AVRCP proxies and binds using the action
`com.neusoft.geely.btphone.service.BtPhoneManagerService`. Its legacy `getSpp()`
returns `null`. The locally supplied `btphoneNF.apk` (package
`com.neusoft.geely.btphone.nf`) contains the concrete
`com.neusoft.geely.btphone.nf.BtManagerService` and
`IBtPhoneManager` Binder stub; its manifest advertises the distinct action
`com.neusoft.geely.btphone.control`. Most decisively,
`BtManagerService$5.getSpp()` returns `null`. Thus the SPP API is present, but this
local manager implementation does not provide an `ISpp` object. The action mismatch
also means the QDrive `BtImpl` bind call is not evidence that it reaches this service.

No QDrive application call site was found that obtains a non-null SPP object,
registers an SPP callback, or sends bytes with `reqSppSendData`. The
`Bluetooth-GocBtAPI.apk` contains the separate NForetek SPP interface/service surface;
the previously audited `NfServiceSpp` path is dead/no-op and is not a valid substitute.

Separately, `libSSP_Main.so` has real exported executable functions named
`SSP_Main_iSPP_Init`, `SSP_Main_iSPP_Connect`, `SSP_MainAPP_iConnectSPP`,
`SSP_CB_SPP_Connnect_WIFI`, `SSP_CB_SPP_ConnnectState_USB`, and
`SSP_CB_BTSPP_*`. This is **STRONG EVIDENCE** of QDrive's own compiled SPP-like
control logic, not just strings. However, the Java native methods
`JNI_SSP_MainAPP_iConnectSPP` and `JNI_SSP_MainAPP_vStartWifiTcpConnect` have no
Java call sites in this APK. Their internal native reachability and relationship to
the ECARX Binder or the vehicle's `GEELY_BT`/NForetek service remain **UNKNOWN**.
These symbols do not prove a working generic Bluetooth byte transport.

**Wi-Fi and AirPlay — STRONG EVIDENCE for QDLink/mirroring, not CarPlay.**

`MainSDKService` prepares HCLink data and calls
`SSPMainSDK.JNI_SSP_MainAPP_iStartServer(context)`. It registers native callbacks
for USB state, Wi-Fi connect/disconnect, connection state, and AirPlay service state.
`LinkCallBack.SSPMainConnect_wifi(int, int, int)` maps connection status to
`link_conn_iphone_wifi` or `link_conn_android_wifi` and updates the mirror-data type;
it is a status callback, not a credential exchange. When the service's activation
network checks pass, `MainSDKService.checkActivateNetStatus()` calls
`JNI_SSP_MainAPP_vStartAirplayActivate()`.

The native `libSSP_Main.so` exports a QD Wi-Fi channel with UDP broadcast/search,
system-info exchange, Wi-Fi/TCP connect, mirror-TCP-port exchange, and separate app/
mirror receive threads. `libSSPAirPlay.so` depends on `libmediaserver.so`; the latter
contains RAOP and Bonjour/mDNS AirPlay service strings. This establishes a compiled
wireless mirroring/AirPlay stack. It does **not** establish wireless CarPlay.

`QDAssistantAPI.requestSendWifiAddress(String)` serializes a string under the
`WIFIADDRESS` message key and passes it to
`JNILibCore.Lib_Core_SSPLink_iSendData`. The method has no QDrive DEX call site.
The payload is named as an address, not an SSID or passphrase; no active credential
exchange is proven. The Java DEX contains no Wi-Fi client `addNetwork` /
`enableNetwork` flow. The embedded ECARX `WifiApBean` code manages AP configuration
and client limits, but is not evidence that QDrive provisions an iPhone's Wi-Fi
credentials.

The DEX does contain the `CarPlay` and `carplay_audio` strings in
`com.neusoft.optimus.utility.Storage.Constant`. The executable `CarPlay` comparison
is part of a generic source-name validation alongside radio, USB, Bluetooth audio,
and video inputs; `carplay_audio` is a constant. No CarPlay session, authentication,
or native-engine call follows from these strings. They are **REFERENCE ONLY**, not
evidence that QDrive implements CarPlay.

#### Native-library relationships

All nine libraries below are ELF32, little-endian ARM shared objects. `DT_NEEDED`
edges and representative exported interfaces were checked statically.

| Library | Direct `DT_NEEDED` dependencies | Relevant evidence |
|---|---|---|
| `libautoregister.so` | `liblog.so`, `libm.so`, `libdl.so`, `libc.so` | Signature/helper dependency of the USB and main SSP libraries; not an Apple authentication engine. |
| `libhmi_for_hclink.so` | `liblog.so`, `libGLESv2.so`, `libjnigraphics.so`, `libOpenSLES.so`, `libc.so`, `libstdc++.so`, `libandroid.so`, `libm.so`, `libdl.so` | HCLink display/audio integration dependency of `libSSP_Main.so`; not CarPlay transport. |
| `libmediaserver.so` | `liblog.so`, `libandroid.so`, `libstdc++.so`, `libm.so`, `libc.so`, `libdl.so` | RAOP/AirPlay and mDNS strings. Contains embedded private-key-looking PEM material; its value is intentionally omitted and the binary should not be republished. |
| `libSSPAirPlay.so` | `libmediaserver.so`, `liblog.so`, `libc.so`, `libm.so`, `libstdc++.so`, `libdl.so` | JNI media-server wrapper; dependency edge to the AirPlay/RAOP implementation. |
| `libSSPAirPlayUSB.so` | `libusbserver.so`, `libautoregister.so`, `liblog.so`, `libc.so`, `libm.so`, `libstdc++.so`, `libdl.so` | JNI USB wrapper; imports `UsbScreen_*` functions implemented by `libusbserver.so`, including device add and USB service start. |
| `libsspLib.so` | `libstdc++.so`, `libm.so`, `libc.so`, `libdl.so` | SSP data serialization helpers; not independently identified as a phone transport. |
| `libsspLibCore.so` | `liblog.so`, `libstdc++.so`, `libm.so`, `libc.so`, `libdl.so` | JNI `SSPLink` init/send/callback APIs; includes `CProcessClient::SendDataToMainProcess`, so its API alone does not prove a Bluetooth radio path. |
| `libSSP_Main.so` | `libhmi_for_hclink.so`, `libcrypto.so`, `libssl.so`, `liblog.so`, `libautoregister.so`, `libusbserver.so`, `libc.so`, `libm.so`, `libstdc++.so`, `libdl.so` | JNI entry points, executable SPP-like routines, QD Wi-Fi channel, USB callbacks, and AirPlay activation. The JNI USB wrapper is a separate library path. |
| `libusbserver.so` | `liblog.so`, `libz.so`, `libm.so`, `libdl.so`, `libc.so` | Exports the USB server API and contains libusb/usbmuxd/Lockdown pairing and session code. No CarPlay/MFi engine was established. |

The QDrive APK's nine-library directory does not include the separate
`vendor-apks/libipod.so` file, and none of these nine has it in `DT_NEEDED`.
`ipod_callback`/iPod-related symbols in `libSSP_Main.so` therefore do not prove that
QDrive loads that separate library. The iPod callback and `vSendiApMirrorMsg2Phone`
names are not evidence of CarPlay. No native code was executed.

The DEX declares the corresponding library loads: `SSPMainSDK` loads
`hmi_for_hclink` and `SSP_Main`; `JNISSPAirPlayUSBSDK` loads `usbserver` and
`SSPAirPlayUSB`; `JNISSPAirPlaySDK` loads `mediaserver` and `SSPAirPlay`;
`JNILibCore` loads `sspLibCore`; and `SSPProtocol` loads `sspLib`. These are
QDrive's APK JNI dependencies, not a load path for `libipod.so`.

#### Reuse assessment and limits

| Component | Classification | Assessment |
|---|---|---|
| `PhoneMux` USB-host enumeration, permission, and file-descriptor handoff | REIMPLEMENTABLE | A concrete direct-iPhone USB-host pattern, but bound to QDrive's JNI and native server. |
| `libSSPAirPlayUSB.so` + `libusbserver.so` | QDRIVE-COUPLED | Demonstrates QDrive's direct USB Lockdown/mirroring transport; not a documented DiPlay API or a CarPlay engine. |
| QD Wi-Fi/UDP/TCP/AirPlay stack | QDRIVE-COUPLED | Useful architecture evidence for direct wireless mirroring; not a CarPlay session implementation. |
| ECARX `bt1.ISpp` / `ISppCallback` | UNKNOWN / not callable on this local build | Byte-array Binder contract exists, but the local manager returns `null`; no QDrive send/receive call chain. |
| NForetek `NfServiceSpp` | NOT RELEVANT | Previously established dead/no-op path; do not bind or rely on it. |
| RAOP/mDNS/AirPlay media server | NOT RELEVANT to CarPlay protocol | Useful only as a QDrive A/V integration reference. |
| iAP2/MFi/CarPlay authentication and session engine | UNKNOWN / not found | The iAP callback name is only an interface notification. USB Lockdown trust is not MFi authentication. |

**Answers and confidence**

1. **USB transport call chain — PROVEN statically for QDrive mirroring, not runtime
   success:** `MainSDKService` →
   `PhoneMux` → Android `UsbManager` permission/open → USB file descriptor →
   `JNI_SSP_AddUsbDevice` → `libSSPAirPlayUSB.so` → `libusbserver.so` →
   usbmuxd/Lockdown. It is direct USB, with no Carlinkit adapter in this chain.
2. **Bluetooth/SPP call chain — UNKNOWN as a working phone transport:** the ECARX
   Binder contract supports arbitrary bytes, but both the QDrive legacy getter and
   the local `btphoneNF` Binder implementation return `null`; QDrive has no byte
   send/receive call site. QDrive's native SPP-like functions are compiled, but their
   active backend and connection to `GEELY_BT` are not established.
3. **Wi-Fi/AirPlay call chain — STRONG EVIDENCE:** `MainSDKService` starts/registers
   `SSP_Main`; the service calls AirPlay activation and receives Wi-Fi/mirror state;
   `libSSP_Main.so` contains QD Wi-Fi discovery and TCP mirror functions; and
   `libSSPAirPlay.so` links to the RAOP/mDNS media server. This is QDLink/AirPlay
   mirroring, not CarPlay. Wi-Fi credential provisioning is **UNKNOWN**.
4. **JNI/native relationships — PROVEN:** `libSSPAirPlayUSB.so` calls the
   `UsbScreen_*` API in `libusbserver.so`; `libSSPAirPlay.so` depends on
   `libmediaserver.so`; `libSSP_Main.so` depends on `libusbserver.so`,
   `libhmi_for_hclink.so`, and its crypto/TLS dependencies. QDrive's Java code invokes
   USB-server and AirPlay-activation entry points; the native SPP/Wi-Fi JNI methods
   noted above have no Java call site in this DEX.
5. **Was ECARX SPP resolved? — PROVEN:** the local manager owner is
   `btphoneNF.apk` / `com.neusoft.geely.btphone.nf.BtManagerService`; its concrete
   `getSpp()` returns `null`. QDrive's old adapter uses a different service action.
   No separate usable ECARX byte-transport implementation was found.
6. **CarPlay implementation — STRONG EVIDENCE not present in this QDrive chain:**
   no CarPlay client/session engine, iAP2/MFi identification/authentication path,
   CarPlay service orchestration, or proven Java/native-to-vehicle SPP bridge was
   identified. Its DEX contains only generic CarPlay source labels. QDrive supplies
   direct iPhone USB Lockdown and AirPlay/QDLink functionality; this does not rule
   out a separate implementation elsewhere in the firmware.
7. **Reusable pieces — PROVEN/PLAUSIBLE:** USB enumeration and descriptor handoff
   are reimplementable. QDrive's native USB and Wi-Fi libraries are
   QDrive-coupled. None is a drop-in CarPlay transport for DiPlay. The Bluetooth API
   is not usable through the local manager because it returns `null`.
8. **Single safest next experiment:** on a parked head unit with **no iPhone
   connected**, use the already-built Phase 3B.8 manual read-only inventory to capture
   paths and hashes for the installed QDrive, `btphoneNF`, and
   `Bluetooth-GocBtAPI` packages and their native-library directories. Stop after
   collection and compare offline on the PC; do not bind/start services or load
   libraries.

**Bottom line:** QDrive contains a direct USB-host/usbmuxd/Lockdown path intended for
QDLink/AirPlay-style functionality; runtime success was not tested. Native Wi-Fi
mirroring is also strongly evidenced. Neither proves CarPlay. The ECARX `ISpp`
contract is not functional on the locally inspected `btphoneNF` service because
`getSpp()` returns `null`; native SPP-like symbols remain unconnected to a verified
`GEELY_BT` transport. No iAP2/MFi/CarPlay authentication engine was established.
No runtime transport test was performed.

### Phase 3C.2 — DiPlay CarPlay session + direct USB integration audit

**Scope:** static source review of DiPlay's USB, Lockdown, iAP2, MFi, NCM, AirPlay,
media, and input paths. No build, test, USB operation, iPhone connection, authentication,
service launch, or native-library load was performed. QDrive is used only as the Phase
3C.1 transport reference; its extracted files were not re-audited here.

**Reachability gate:** this is the intended source call graph, not an enabled runtime
path in the current Geely launch build. `LegacyLaunchBuild.CONNECTIONS_ENABLED` is
`false`; the host controller's `start()` rejects connection startup, and the manifest
declares `CarPlayHostActivity` and `DiPlaySessionService` disabled by default. The
Phase 3B diagnostic surfaces remain distinct from this gated CarPlay runtime.

#### Evidence-based architecture map

Behind that gate, the source describes a wired path with two distinct USB data paths: USBMUX carries
Lockdown and the CarKit service byte stream; the separate NCM function carries the
AirPlay IP network. The main source-level flow is:

```text
CarPlayHostActivity (manual host UI; foreground/session setup)
  -> CarPlayController.start()
  -> startMfi() and resolve the configured MFi provider first
  -> on MFi ready, startPhone()
  -> discover Apple USB device with UsbManager / request host permission
  -> if necessary, vendor control request 0x52 and wait for USB re-enumeration
  -> select the configuration containing USBMUX and NCM
  -> claim USBMUX bulk interface and open USBMUX session
  -> open a second USB connection for the NCM control/data interfaces
     -> select data alternate setting; NcmUsbBridge (NTB16/Ethernet framing)
  -> runStack()
     ├─ Iap2UsbMuxHost (USBMUX v2 + minimal TCP stream)
     │   -> LockdownPairingClient (only on first/unusable pairing record)
     │      -> normal iOS Lockdown pairing/trust flow
     │   -> LockdownCarKitClient
     │      -> Lockdown StartSession + session TLS
     │      -> StartService("com.apple.carkit.service")
     │      -> service TLS when requested; return byte stream
     │   -> Iap2Session / Iap2LinkChannel / Iap2LinkEngine
     │      -> iAP2 link synchronization, framing, ack/retransmit, sessions
     │      -> Iap2CsmChannel / Iap2CsmFramer
     │      -> Iap2WiredControlClient
     │         -> identification -> MFi auth -> power/subscriptions
     │         -> CarPlayAvailability -> CarPlayStartSession
     └─ NcmUsbBridge -> CarPlayVpnService / Ipv6NcmBridge / app-scoped VPN
         -> AirPlay TCP listener at the configured link-local IPv6 endpoint
            -> AirPlaySession (RTSP setup, pairing/verify, stream lifecycle)
            -> CarPlayMediaEngine
               ├─ screen/audio/iAP DataStream ports -> MediaSink
               ├─ Android MediaCodec / AudioTrack playback
               ├─ optional AudioRecord microphone uplink
               └─ AirPlay HID touch, knob, media and telephony input
```

MFi provider startup is before iPhone discovery in the wired controller: `start()`
calls `startMfi()`, and the provider's ready path calls `startPhone()`.

The order is important: Lockdown pairing and service startup are **not** iAP2;
USBMUX is **not** NCM; and opening `com.apple.carkit.service` is not itself proof
that the phone accepts DiPlay's later iAP2 identification or CarPlay session. The
controller opens the CarKit stream, wraps it in `Iap2Session`, attaches the NCM/VPN
AirPlay path, and then runs wired iAP2 control. `CarPlayStartSession` does not itself
open the AirPlay transport.

| Layer | Source evidence | Status |
|---|---|---|
| **TRANSPORT — USB host** | `IphoneUsbHost` discovers Apple VID `0x05ac`, requests Android USB permission, selects descriptor-based CarPlay configuration, claims interfaces, and implements the vendor request/re-enumeration step described in source. | Implemented in source; not runtime-verified on the E01 in this phase. |
| **TRANSPORT — USBMUX/Lockdown** | `Iap2UsbMuxHost`, `LockdownPairingClient`, and `LockdownCarKitClient` provide USBMUX v2, a minimal TCP stream, Lockdown pairing, StartSession/TLS, and StartService. | Implemented minimum flow; the iOS Trust prompt is expected for new pairing and is not bypassed. |
| **TRANSPORT — NCM** | `IphoneCarPlayConfiguration`, `NcmFunctionDiscovery`, `NcmUsbBridge`, and `Ntb16Codec` implement CDC-NCM discovery and NTB16 Ethernet framing. | Implemented subset; Android 5.1 async USB API incompatibility below prevents claiming API 22 operation. |
| **iAP2** | `Iap2LinkEngine` handles marker, synchronization, checksums, sequence/ack handling, retransmission and bounded queues; `Iap2Session`, `Iap2LinkChannel`, `Iap2CsmChannel`, and CSM codecs provide the byte/session/control-message layers. | Substantial implemented subset, not evidence of complete conformance to every iAP2 feature. |
| **AUTHENTICATION — iAP2 MFi** | `Iap2MfiAuthenticationClient` implements AA00–AA05 certificate/challenge/signature exchange. `MfiAuthenticator` has local-file, I2C coprocessor, USB-CH341 and remote provider implementations. | Protocol/client code exists; a trusted, legitimate identity/provider is deployment-required and not established for the E01 by this source audit. |
| **CARPLAY SESSION** | `Iap2WiredControlClient` sequences identification, authentication, power, subscriptions, availability, and start-session control. | Implemented path in source; iPhone acceptance/interoperability unverified. |
| **MEDIA / AirPlay** | `CarPlayVpnService`, `AirPlaySession`, `CarPlayMediaEngine`, media stream classes and `AndroidMediaSink` cover the IP listener, RTSP session/stream setup, encrypted event channel, video/audio/DataStream, decoding/playback and optional microphone. | Substantial source implementation; not runtime-verified in this phase. |
| **INPUT** | `CarPlayHostActivity` forwards touch through `CarPlayController` to `AirPlaySession`; `AirPlayHid` encodes touch and other HID reports. | Implemented in source; device mapping/interoperability unverified. |

#### Transport boundary and relation to QDrive

The transport seam for protocol byte streams is
`BlockingDuplexByteStream`, consumed by `Iap2UsbMuxHost`, Lockdown/TLS clients,
and `Iap2Session.open`/`Iap2Session.openTunnel`. It is a useful insertion point for
another compatible stream implementation, but it is not the whole direct-USB contract:
the wired design additionally requires a working NCM Ethernet bridge for AirPlay TCP/IP.
DiPlay already has Android `UsbManager` implementations for both interfaces, so the
QDrive native usbmuxd path is not needed merely to obtain direct USB access.

QDrive's `UsbManager -> FD -> JNI -> libSSPAirPlayUSB/libusbserver -> usbmuxd/Lockdown`
and DiPlay's Java USBMUX/Lockdown are related at the broad USBMUX/Lockdown layer only.
Lockdown by itself does not supply iAP2, MFi accessory authentication, CarPlay control,
or the NCM/AirPlay network. DiPlay's source explicitly places iAP2 on the byte stream
returned by Lockdown `StartService`, and places AirPlay IP traffic on NCM. QDrive's
implementation is therefore useful as proof of a stock USB-host precedent, not as an
Apple CarPlay session engine or a proven drop-in transport.

Wireless is a separate implementation: DiPlay has Bluetooth RFCOMM/iAP2 bootstrap,
wireless control, and AirPlay type-130 tunnel/handoff paths. This does not create a
verified E01 `GEELY_BT` bridge, and this phase makes no claim that the vehicle Bluetooth
stack is a usable iPhone bootstrap transport.

#### Authentication boundary and open-source build

MFi authentication is required by the wired controller's sequence before its CarPlay
availability/start-session steps. `Iap2MfiAuthenticationClient` sends an accessory
certificate in response to AA00, signs the AA02 challenge via the selected
`MfiAuthenticator`, and waits for AA05; it reports AA04 as failure. The AirPlay
`/pair-setup` and `/pair-verify` procedures are a separate AirPlay session security
layer and do not replace iAP2 accessory authentication.

Available providers have materially different requirements:

* `LocalMfiAuthenticationClient` loads an explicitly provisioned identity/certificate
  and checks that the EC key matches the certificate. Its own source says this
  self-consistency check does **not** establish iPhone trust.
* I2C/USB-CH341 providers require a reachable MFi authentication coprocessor; the
  UI/configuration includes a deployment-supplied CH341 USB bridge or Linux I2C path.
  This audit did not establish such hardware in the E01.
* `RemoteMfiAuthenticationClient` delegates certificate/signing operations to a
  configured remote endpoint; it is not a built-in credential or an offline solution.

The normal source build intentionally contains no accessory identity. `mobile/build.gradle.kts`
accepts local authentication assets only through the explicit local-only
`DIPLAY_AUTH_ASSETS_DIR` input, and the standalone-debug task checks that those assets
were supplied. No credential material was inspected or reproduced for this audit.
Accordingly, the open-source build contains the authentication protocol plumbing, but
cannot, on its own, complete a trusted MFi exchange.

#### API 22 compatibility blockers

The declared `minSdk` is 22, but the direct USB read path calls newer Android framework
methods without API guards:

* `IphoneUsbHost` calls `UsbRequest.queue(ByteBuffer)` and
  `UsbDeviceConnection.requestWait(long)`.
* `NcmUsbBridge` calls the same `queue(ByteBuffer)` and timed `requestWait(long)` APIs.
* Lockdown pairing/plist/TLS code (`LockdownPairRecord`, `LockdownPlistChannel`,
  and `LockdownTlsEngineFactory`) uses `java.util.Base64`, which is not available in
  the Android framework until API 26. No core-library desugaring configuration was
  found. This affects the wired path before iAP2 can start, not just the optional
  remote MFi provider.

These overloads were added in API 26. Android 5.1/API 22 provides the older
`queue(ByteBuffer, int)` and untimed `requestWait()` forms instead. As written, the
direct USBMUX/Lockdown/NCM path cannot be considered API 22 compatible; invoking it on
Android 5.1 risks missing-method/class runtime failures. A compatibility implementation
must provide Base64 support and preserve bounded cancellation/close behavior despite
the older untimed USB request wait.

There is a further transfer-size constraint: the code queues 64 KiB USB read buffers
and NCM permits NTB16 blocks up to the 16-bit limit. Before Android 9/API 28, Android's
USB transfer APIs cap an individual transfer at 16 KiB. The API 22 port must therefore
also handle smaller request/transfer chunks and reassembly/termination correctly rather
than simply swapping method overloads.

Other inspected newer-API usage is generally guarded (for example typed parcelable
retrieval/dynamic receiver flags on API 33, Bluetooth runtime permissions on API 31,
and microphone blocking reads on API 23). These guards do not remove the API 26
USB-request and Base64 blockers. `Iap2LocationClient` also uses `java.time.Instant`
(API 26) for location messages; this must be avoided or desugared if that optional
feature is used on API 22. The USB permission `PendingIntent` includes `FLAG_IMMUTABLE`,
introduced after API 22; although that flag is passed as an integer bit, its behavior
on the target vendor Android 5.1 build should be included in compatibility verification.

Android API references: [UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest),
[UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection),
[java.util.Base64](https://developer.android.com/reference/java/util/Base64), and
[java.time.Instant](https://developer.android.com/reference/java/time/Instant).

#### Minimum wired path and component disposition

| Required component | Disposition |
|---|---|
| Apple device discovery, permission, USBMUX/NCM descriptor selection | **ALREADY IMPLEMENTED** in Java; **API 22 PORTING REQUIRED** for async USB operations and transfer sizing. |
| USBMUX v2 plus Lockdown TCP/plist and first-time Trust pairing | **PORTABLE FROM EXISTING DIPLAY**; QDrive is not required for these layers. |
| CarKit service StartSession/TLS and byte-stream handoff | **PORTABLE FROM EXISTING DIPLAY**; requires successful Lockdown pairing and service support from the phone. |
| iAP2 link, CSM framing, identification and wired control subset | **ALREADY IMPLEMENTED** in source; actual iPhone acceptance remains unverified. |
| MFi certificate and challenge signing | **REQUIRES LEGITIMATE EXTERNAL COMPONENT**: a trusted provisioned identity or supported MFi coprocessor/provider. Availability on the E01 is **UNKNOWN**. |
| NCM IPv6 bridge, AirPlay listener/session and media/input | **ALREADY IMPLEMENTED** in source; port to API 22 and end-to-end behavior remain unverified. |
| QDrive's native usbmuxd/Lockdown libraries | **QDRIVE-COUPLED**; not needed if DiPlay's Java USBMUX/Lockdown passes compatibility and interoperability tests. |

The shortest wired route is not to write a new CarPlay engine or load QDrive's
proprietary native libraries. First make the existing USBMUX and NCM paths genuinely
API 22 compatible, then establish a legitimate MFi authentication target, then validate
the existing Lockdown → CarKit → iAP2 control → NCM/AirPlay handoff in isolated,
incremental tests. A source-level path is present, but none of these code paths was
executed in this audit.

#### Findings and safest next step

1. **Existing architecture:** Java Android USB host; USBMUX/Lockdown; iAP2/CSM and wired
   control; separate NCM/VPN/AirPlay; media and HID input are all represented in source.
2. **Existing transport:** not a dongle transport abstraction; wired mode uses two iPhone
   USB interfaces (USBMUX and NCM). Wireless mode has a separate Bluetooth bootstrap and
   Wi-Fi/AirPlay path.
3. **Transport insertion point:** `BlockingDuplexByteStream` for Lockdown/service/iAP2
   byte streams, plus a distinct NCM network-interface bridge for AirPlay IP traffic.
4. **iAP2 status:** substantial link, CSM, identification, authentication-message and
   wired-control subset implemented; completeness and peer interoperability unproven.
5. **MFi status:** exchange protocol implemented, but usable legitimate identity/provider
   is external and not established on the E01. The ordinary source build has no identity.
6. **QDrive relevance:** usbmuxd/Lockdown is a lower-level transport precedent only; it
   is not equivalent to iAP2 or CarPlay, and does not replace NCM.
7. **Missing/blocked pieces:** API 22 USB-request adaptation and transfer sizing,
   Java Base64 compatibility for Lockdown, and a legitimate MFi target.
8. **API 22 blockers:** API 26 `queue(ByteBuffer)`/`requestWait(long)` calls in both
   USBMUX and NCM code; `java.util.Base64` in Lockdown; API 28-era 16 KiB transfer limit
   also needs handling.
9. **Minimum architecture:** use DiPlay's Java USBMUX + Lockdown to open CarKit, feed
   that service byte stream to its iAP2 control stack, and use the separate NCM bridge
   to carry the AirPlay network/media path.
10. **Phase 3C.3:** a live end-to-end iPhone experiment is **not yet technically justified**.
    Resolve and test API 22 USB and Base64 compatibility and the legitimate MFi provider
    first. The safest next experiment is PC-side API 22 compatibility work with
    fake/controlled USB stream tests and no attached iPhone; afterward, confirm an
    authorized MFi provider before any separately approved hardware test.

**Verdict: WIRED PATH BLOCKED BY AUTHENTICATION**

This verdict does not mean DiPlay lacks the CarPlay protocol/session code. It means the
source-only audit found no E01-proven legitimate MFi identity/coprocessor, and the
current Geely launch build deliberately gates connection startup, while direct-USB
handling also has API 22 runtime blockers that must be resolved before a controlled
live test.

### Phase 3C.3B — MFi authentication provider feasibility audit

The detailed static report is [PHASE3C3B_MFI_PROVIDER_AUDIT.md](./PHASE3C3B_MFI_PROVIDER_AUDIT.md).
No iPhone, iAP2 session, USB/I2C/Bluetooth operation, vendor service, or
authentication attempt was used for this audit. The runtime connection gate remains
disabled.

DiPlay already implements the AA00–AA05 iAP2 MFi exchange. Its `MfiAuthenticator`
boundary has four provider routes:

| Provider | Source requirements | E01 status |
|---|---|---|
| Local identity | Explicitly provisioned `offline-mfi/identity.pk8` (PKCS#8 P-256 EC private key) and `certificate.p7b` (one X.509 certificate); stored in app-private files. Local signing/key consistency does not prove Apple trust. | No authorized identity in the ordinary build; not established on E01. |
| Direct I2C coprocessor | Accessible `/dev/i2c-N`, packaged `xcertplay_i2c` JNI library, permitted OS/SELinux access, and compatible chip. The source probes 7-bit I2C addresses `0x10` then `0x11`. | No bus, node, access rule, Binder service or responding chip is established by collected artifacts. |
| CH341-to-I2C | External CH341 bridge and compatible MFi coprocessor; deployment-configured VID/PID and Android USB permission. | No deployed bridge identity or attached coprocessor established. |
| Remote provider | Remote service implementing `/mfi/reset`, `/mfi/certificate`, and `/mfi/sign`, backed by an authorized identity/provider. | No authorized endpoint or identity established. The current client transmits a literal `Authorization: ******` placeholder rather than standard token authentication; secure provider authentication would need to match/replace that contract before deployment. |

The `0x10` and `0x11` I2C device addresses must not be confused with coprocessor
registers `0x10`/`0x11`. `MfiDeviceScanner` performs register-select writes and reads;
it is active I2C traffic, not passive discovery, and was not run.

The Geely/Neusoft DEX audit found embedded Apple wrappers, an Apple authentication
state listener, and USB/UEvent/NCM control hooks, but no Java MFi coprocessor or I2C
access path. `AppleCore_jni` and `ApplePrivate_jni` remain unavailable in the collected
artifacts, so native behavior cannot be ruled in or out. QDrive's USBMUX/Lockdown and
its own activation/signature helper are not evidence of Apple MFi certificate/challenge
authentication. Existing collected artifacts lack a complete E01 `/dev`/sysfs
inventory, board documentation, I2C access policy, and vendor I2C service
implementation; static absence does not prove physical absence.

**Minimum missing component:** one authorized identity/signing provider—a compatible
MFi coprocessor via an authorized bus/bridge, correctly provisioned local identity,
or legitimate remote provider. No protocol redesign or authentication bypass is
appropriate.

**Phase 3C.3C hardware detection is not justified yet.** First gather read-only
authoritative bus/node/service/access evidence. Only a documented candidate path
should be considered for a separately approved, narrowly scoped detection test.

**Phase 3C.3B verdict: INSUFFICIENT EVIDENCE.** This is a hardware/provider evidence
gap, not a claim that the physical E01 definitely lacks an authentication chip.

### Phase 3C.3C — E01 passive MFi/I2C hardware inventory

Settings > Diagnostics now exposes **Run passive MFi/I2C inventory** as a manual
action. It does not run on launch or when Settings opens. The inventory is limited
to direct `/dev` children matching `i2c-N`, `/sys/class/i2c-dev/`, and
`/sys/bus/i2c/devices/`. It reports node type, `canRead`/`canWrite`, mode/owner
metadata when available, sysfs buses and registered client addresses, and bounded
`name`, `modalias`, and `uevent` text plus symlink targets/resolved paths. Permission
and read errors are retained in the displayed report. The report is included in
Save diagnostic report.

The implementation uses `lstat`, access checks, directory enumeration, readlink,
canonical-path checks and bounded reads of ordinary sysfs text files only. It does
not open `/dev/i2c-*`, issue ioctl, access `I2cTransport`, run `MfiDeviceScanner` or
`LocalMfiProbe`, instantiate an authentication client, or interact with USB,
Bluetooth, an iPhone, vendor services, or CarPlay. Sysfs-resolved paths are
restricted to `/sys`; unrelated `/dev` and `/sys` trees are not enumerated.
Keyword matches merely flag identifying text; generic addresses (including
`0x10`/`0x11`) are explicitly not treated as MFi evidence.

Fake-filesystem tests assert that only the three approved directories are listed,
device nodes are never read, sysfs reads remain bounded, failures are reported, and
no I2C/authentication transport can be reached through the inventory's filesystem
only dependency boundary. The connection gate remains
`LegacyLaunchBuild.CONNECTIONS_ENABLED = false`; minSdk remains 22. This inventory
does not establish whether any reported generic device is an Apple authentication
coprocessor. Stop after collecting/exporting the report; do not run the active
scanner or proceed to authentication.

### Phase 3D.1 — Passive direct iPhone USB enumeration

Real-E01 Phase 3C.3C testing found `/dev/i2c-0` through `/dev/i2c-3`, all
root-only `0600`, and no Apple/MFi/authentication evidence in registered sysfs
clients. The onboard-I2C MFi route is deferred; no active scanner/probe is approved.

The new Settings/Diagnostics action **Scan connected USB devices** observes
Android's existing USB host list only, using API22-compatible `UsbManager`
device-list and permission-status getters plus device/interface/endpoint
descriptor getters. The injected inventory interface exposes only detached
metadata snapshots, with no device connection or transport capability.
No USB permission is requested, no device is opened, no interface is claimed,
and no transfer, USB role/mode change, vendor invocation or authentication occurs.
An Apple VID `0x05AC` is labeled **Apple USB device candidate**, not CarPlay proof.
Results and explicit enumeration errors appear in the UI and saved diagnostic
report. Nothing runs automatically; all existing connection gates stay disabled.

Tests exercise the API22-compatible Android adapter in the repository's SDK28
Robolectric harness (installed Robolectric does not support SDK22), allow-list its mock interactions,
check compiled passive classes for prohibited USB/transport dependencies, and
verify manual UI execution and report export. Follow the baseline/direct-cable
procedure in [TESTING.md](TESTING.md#phase-3d1--passive-direct-iphone-usb-enumeration).
Stop after Phase 3D.1; no Lockdown, usbmuxd, iAP2, MFi, NCM or CarPlay follow-up.

### Phase 3D.2 — Direct USBMUX/Lockdown transport test

Phase 3D.1 passed on the real E01 with Carlinkit completely removed: the unlocked
direct iPhone was visible as one Apple `05AC:12A8` device with 12 interfaces and
existing Android USB permission. The `255/254/2` interface exposed BULK OUT
`0x04` and BULK IN `0x85`, max packet size 512. This establishes enumeration,
not yet working USBMUX/Lockdown communication.

#### Existing path audit and reuse

* `IphoneUsbMatcher.appleVendor()` / `IphoneUsbHost.discover()` select Apple VID.
  The diagnostic uses the same VID, requires exactly one Apple device, and never
  guesses between phones.
* `IphoneCarPlayConfiguration.isUsbMuxInterface()` identifies `255/254/2`;
  `usbMuxEndpoints()` prefers the evidenced `0x04/0x85` BULK pair, with the
  existing unique BULK OUT/IN fallback for other models. `255/253/1` is the
  existing Apple Ethernet signature, **not USBMUX**. Neither packet size 512 nor
  an interface index alone identifies the transport.
* The ordinary `IphoneUsbHost.openIap2UsbSession()` calls `setConfiguration`;
  its re-enumeration path sends vendor control request `0x52`. Those paths are
  deliberately **not called or instantiated** by this diagnostic.
* `AndroidDirectUsbMuxAccess` adds only the scoped opener for the currently
  exposed interface: permission is checked first, then `openDevice` and
  `claimInterface(selected, false)`. No kernel-driver force detach,
  configuration/alternate-setting switch or control transfer is attempted.
* The existing `Iap2UsbSession` performs bulk writes and request-based reads,
  retaining `UsbTransferCompatibility`'s API22 queue/wait path and 16 KiB limit.
  It now implements the injectable `UsbMuxBulkPipe` interface. Partial writes
  share one logical write deadline. On diagnostic close, pending reads are
  cancelled, only the claimed interface is released, and the device is closed.
* Existing `Iap2UsbMuxHost` / `UsbMuxFrameBuffer` implement version-2 exchange,
  setup and framed TCP. The diagnostic supplies a 5-second handshake timeout;
  the normal host default remains unchanged. Only port `62078` is connected.
* Existing `LockdownPlistChannel` implements four-byte big-endian XML plist
  lengths, bounded messages and partial-stream reads. The existing
  `LockdownPairingClient.getValue()` demonstrates `GetValue` request shape, but
  that client is **not instantiated**: its ordinary workflow performs
  `SetValue(UntrustedHostBUID)` and `Pair`. The diagnostic sends a single
  plaintext `GetValue` with only `Key=ProductType` through the existing channel.

The response must identify `Request=GetValue` and contain a text model
`Value`; only bounded safe model metadata is displayed. No UDID, serial number,
Wi-Fi address, public key, full plist or pairing record is dumped. A remote error
is reported without retries or pairing. Android cannot reliably observe an
iPhone-screen Trust prompt: the UI provides **Trust prompt appeared — STOP** to
record the user's observation and cancel; never approve it.

All access is manual through **Test USBMUX + Lockdown**. Step deadlines are
5 seconds (ordinary framed writes use the existing 2-second bound), the watchdog
closes the pipe at 25 seconds, and cancel/activity pause/destroy close active
access. UI and saved reports contain selection, permission, open/claim, handshake,
connect/query, cleanup, failure stage and an evidence-based verdict. The strict
diagnostic boundary excludes pairing, ValidatePair, pairing records, Trust
mutation, StartSession, TLS, CarKit, iAP2, MFi, NCM, Bluetooth, vendor
services/JNI, AirPlay and CarPlay. `CONNECTIONS_ENABLED` remains false, minSdk 22.
Stop after the test/report; no Phase 3D.3 is enabled.

### Phase 3D.2A — Passive iPhone USB configuration mapping

The real E01 Phase 3D.2 result stopped at descriptor selection for Apple
`05AC:12A8`: no unambiguous `255/254/2` BULK pair, no device open and no Lockdown
attempt. Phase 3D.1 reported two identical-looking flattened candidates at indices
6 and 8, each interface ID 1 with BULK OUT `0x04` and IN `0x85`. Those indices are
not selectors and the supplied results do not establish configuration membership
or alternate settings.

#### Static selection audit (no runtime selector change)

| Concern | Existing behavior | Implication for the E01 sample |
|---|---|---|
| Multiple configurations | `IphoneCarPlayConfiguration.find()` enumerates every configuration, prefers the first with USBMUX + CDC NCM + Apple Ethernet, then USBMUX + CDC NCM. | This is descriptor-composition selection for the older active CarPlay path, not discovery of the current configuration. |
| Configuration IDs | IDs are logged and the selected configuration object is passed to `setConfiguration()` in `IphoneUsbHost`. The selector does not hard-code an ID. | An ID identifies a descriptor configuration; ordering or ID does not prove it is active. This active opener must not be used in 3D.2A. |
| Alternate settings | `describe()` and the opener log `alternateSetting`; USBMUX matching ignores it and returns the first matching interface within a configuration. No `setInterface()` occurs in these selection/open paths. | There is no active-alt-setting selection or passive current-alt-setting query here. Mapping reports every exposed alt value. |
| Apple `0x12A8` | `IphoneUsbMatcher.appleVendor()` accepts any Apple VID; a separately configured matcher can accept exact VID/PID pairs. Neither the configuration selector nor the direct selector special-cases PID `0x12A8`. | The real product ID alone cannot resolve the duplicate candidates. |
| Direct Phase 3D.2 selector | `DirectUsbMuxSelection.find()` searches the flattened device interface list for `255/254/2`, uses the existing BULK pair logic, requires positive max-packet sizes and exactly one candidate. It does not enumerate configurations or identify the active one. | Two matching flattened candidates correctly cause a safe stop before open. This behavior remains unchanged. |
| Active/current configuration | Neither selection path obtains current configuration/alternate-setting state using passive getters. | 3D.2A must report UNKNOWN and not guess. |

The manual **Map iPhone USB configurations** action extends the same injectable
passive snapshot boundary. For each Apple device it reads `getConfigurationCount`
and every configuration's array index, ID, nullable name, public attribute flags,
max power in milliamps, interface count, and configuration-scoped interface/alt
and endpoint descriptors. These getters were introduced by API21 and are available
on API22. Public `UsbConfiguration` does not expose raw `bmAttributes`: the report
explicitly marks it unavailable and supplies only the exposed self-powered and
remote-wakeup bits (mask `0x60`), not a fabricated raw attribute byte.

The flattened interface inventory is retained. The correlation section lists
`configuration -> interface -> alt setting -> endpoints`, all full-descriptor
matches in the flattened view, and the inverse matches for every USBMUX-signature
candidate. Different configuration entries, alternate settings within a config/
interface ID, endpoint differences and descriptor-identical repeated entries are
reported as observations. Descriptor equality is not Android object identity:
identical matches cannot be uniquely assigned and repeated entries do not prove
an Android duplication bug.

**Conclusion before the real mapping report:** configuration metadata can
distinguish candidates by configuration membership or alternate-setting values
if those differ. The supplied flattened E01 report cannot establish which case
applies. Even different configuration IDs would not identify the active one.
Collect the real 3D.2A report before changing Phase 3D.2 to select anything.

Nothing runs automatically; no device is opened, permission requested, interface
claimed, configuration/alt setting changed, transfer performed or USB request
created. No USBMUX, Lockdown, Trust/pairing, iAP2, MFi, NCM, Bluetooth, AirPlay or
CarPlay starts. The output is included in Save diagnostic report and the connection
gate remains false. Tests cover multiple configurations, reused interface IDs,
alternate settings, the observed 12-entry flattened layout with candidates 6/8,
ambiguous equality, descriptor differences, getter-only Android interaction,
manual UI execution and export. Test configuration splits are hypotheses, not
claimed real E01 descriptors.

### Phase 3D.2B — Read-only active iPhone USB configuration

Real-E01 Phase 3D.2A confirmed Apple `05AC:12A8` exposes configuration 1 `PTP`,
2 `iPod USB Interface`, 3 `PTP + Apple Mobile Device`, and 4
`PTP + Apple Mobile Device + Apple USB Ethernet`. Configurations 3 and 4 both
contain interface ID 1, alt 0, `255/254/2`, BULK OUT `0x04` and IN `0x85`.
This explains the duplicate flattened USBMUX candidates without implying which
configuration is currently active. Config 4 also exposes interface ID 2
`255/253/1` alternate-setting descriptors for Apple USB Ethernet.

The new manual **Read active USB configuration** diagnostic selects exactly one
Apple device, records VID/PID, verifies existing permission, rechecks attachment/
identity/permission immediately before open, and opens without claiming any
interface. `AndroidActiveUsbConfigurationAccess` exposes only one request:
standard DEVICE_TO_HOST / DEVICE recipient `GET_CONFIGURATION`, request type
`0x80`, request `0x08`, value/index 0, length 1, timeout 1000ms. This uses the
API22-compatible controlTransfer overload. The connection is closed in `finally`
immediately after the request, before descriptor correlation. No retry or other
control request is issued. Negative, zero-length or non-one-byte transfer results
are explicit failures; no configuration is inferred from array order.

The returned unsigned byte is `bConfigurationValue`, matched to exactly one
configuration ID in the freshly collected passive descriptors. Zero means
unconfigured, not config-array index 0. Unknown/duplicate IDs are correlation
errors, not guesses. The report lists the matching name and scoped USBMUX
interface/endpoints and Apple USB Ethernet interface/alternate-setting descriptors
when present. Alternate-setting descriptors do not identify the current alternate
setting; no interface or alternate setting is activated.

Nothing runs automatically and results are included in Save diagnostic report.
Missing permission stops without requesting it. No claim, setConfiguration,
setInterface, bulk transfer, USB request, USBMUX, Lockdown, Trust/pairing, session,
CarKit, iAP2, MFi, NCM, Bluetooth, vendor/AutoKit/Carlinkit, AirPlay or CarPlay
operation occurs in this path. The existing Phase 3D.2 selector is unchanged;
do not rerun it yet. minSdk stays 22 and CONNECTIONS_ENABLED stays false.

### Phase 3D.2C1: QDrive Valeria branch discriminator

The [native call-site audit](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md)
resolves the comparison PLT entry to **strstr**, not strcmp. It searches
case-sensitively for `Valeria` in the first alternate's interface string,
across every advertised configuration and interface. The first substring
match returns helper TRUE -> configuration-selection branch. Exhaustion
returns FALSE -> vendor-request branch.

The manual **Inspect iPhone interface strings** diagnostic reports cached
configuration/interface names separately, then requires existing permission
and opens without claims. It reuses GET_CONFIGURATION, validates raw
descriptors, fetches only first-LANGID and relevant iInterface string
descriptors using standard read-only requests (1000ms timeout), and closes
before reporting its verdict. C2 corrects missing-index and individual
native-read failure handling as described below. It never invokes vendor code or either branch,
and all runtime gates remain disabled. The real E01 result is still pending.

### Phase 3D.2C2: complete the native-equivalent iteration

Real E01 C1 opened `05AC:12A8`, read active config 1/PTP and string `"PTP"`,
but aborted at config 2/interface 0/iInterface=0. Native re-audit resolves
buffer initialization to `__aeabi_memclr8(buffer,255)`, not an 0xFF fill.
Index zero returns -2 without writing the cleared buffer; negative reads and
native-rejected string descriptors also leave it empty. QDrive then searches
the empty output and continues, not aborts.

The corrected read-only diagnostic reports NO_STRING_INDEX, STRING_READ_PASS,
STRING_READ_FAILED or MALFORMED for each first-alternate entry, plus substring
result and continue/terminate decision. It reaches configurations 3/4 after
the missing config-2 index and preserves immediate success on a valid match.
No string/LANGID cache masks repeated native per-entry reads. A complete
no-match scan with only known-empty failure paths supports helper FALSE;
unresolved malformed native memory behavior, Android exceptions, raw-layout
failure or failed cleanup cannot prove FALSE.

See the [C1/C2 native audit](PHASE3D2C1_QDRIVE_VALERIA_DISCRIMINATOR.md).
Neither resulting branch is performed by the C2 diagnostic. The subsequent
real-E01 report establishes FALSE: PTP, Apple USB Multiplexor and AppleUSBEthernet
strings contain no Valeria. The [3D.2D static audit](PHASE3D2D_QDRIVE_VENDOR_REQUEST_AUDIT.md)
recovers OUT `40/52/value0/index2/length0` without proving the resulting state.

### Phase 3D.2E: controlled vendor-request observation

The [manual controlled test](PHASE3D2E_QDRIVE_VENDOR_TRANSITION_TEST.md)
adds a distinct warning-colored button, successful read-only preflight and
confirmation before one audited request. Its 1000ms timeout intentionally
differs from QDrive's unbounded0. No retry/configuration selection/claim/bulk
or transport follows. Fresh USB inventory and permitted read-only active
configuration/interface-string inspection determine the actual post-state;
return0 does not prove transition. Normal connections remain disabled.
The subsequent user-reported real-E01 test observed detach/reattach after
approximately0.9 seconds, same `05AC:12A8`, five configurations/18 flattened
interfaces, exact Valeria TRUE, but active configuration still1/PTP.
Config5 advertises USBMUX, Valeria and genuine CDC-NCM. No transport followed.
The [Phase3D.2F static audit](PHASE3D2F_QDRIVE_POST_VALERIA_CONFIGURATION_AUDIT.md)
proves QDrive next targets configuration value5 from bNumConfigurations.
DiPlay's configuration-scoped selector recognizes that layout; its older
flattened USBMUX diagnostic remains potentially ambiguous. Activation5 is
not yet tested; Phase3D.2G is designed only. Normal runtime remains disabled.

### Phase 3D.2G: manual configuration5 activation test

The [configuration-only implementation](PHASE3D2G_QDRIVE_CONFIGURATION5_TEST.md)
is now available as a separate confirmed manual diagnostic. It validates the
already-transitioned config5 descriptor/string state and active1, selects the
actual Android configuration object ID5 once, then performs standard readback
and bounded USB observation. No driver detach, claims, transport, vendor
request, retry or restore. Real-E01 activation was untested at build time;
the subsequent user-reported PASS is recorded below. No subsequent transport
phase is implemented. Normal connection gates remain disabled.

### Phase 3D.2H: active config5 USBMUX integration audit

The subsequent user-reported G real-E01 test passed: settertrue, immediate/
final GET_CONFIGURATION5, stable enumeration and no claims or transport.
The [H static audit](PHASE3D2H_ACTIVE_CONFIG5_USBMUX_AUDIT.md) verifies API22
configuration-scoped interface traversal and numeric native claim semantics.
The proposed I test verifies active5, claims config5 ID1/alt0 with forcefalse,
releases/closes and STOPs without bulk. No I implementation or hardware
operation was added; normal connections remain disabled.

### Phase 3D.2I: controlled active5 USBMUX claim/release

The [separate confirmed claim-only diagnostic](PHASE3D2I_ACTIVE_CONFIG5_USBMUX_CLAIM_TEST.md)
is implemented in version `0.2.12-api22-phase3d2i-usbmux-claim`.
It requires existing permission, five configurations, scoped config5
Valeria/USBMUX/NCM evidence and same-connection GET_CONFIGURATION5.
Only config5 interface1/alt0 is claimed once with forcefalse, released once
if claimtrue and closed in finally. No setter/vendor/alternate/driver detach
or bulk/USBMUX/Lockdown/projection follows. Hardware result remains pending.
47 focused tests and the debug build passed; built minSdk22/signing verified,
normal connections remain disabled. Leave the iPhone Trust prompt untouched.
