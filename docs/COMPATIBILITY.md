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

The three Java definitions and related support classes reside in Settings' single
DEX; the same APK has **no native `.so` entries**. `ApplePrivate.setInterface(int)`
also relies on hidden network-management APIs; `AppleService` uses `UEventObserver`.
Thus "class present" does not mean "self-contained", installed standalone package,
working native transport or a safely callable getter. No fallback library name,
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
namespace is not Android package registration. The Settings manifest package is
`com.neusoft.optimus.wheeljack.setting`; it contains no AppleService or
Apple BootCompletedReceiver registration and no exact action filter for
`com.neusoft.appleservice`. Therefore this is an embedded SDK/legacy/external
target reference, **not proof of an actual installed Android package or service**.
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
