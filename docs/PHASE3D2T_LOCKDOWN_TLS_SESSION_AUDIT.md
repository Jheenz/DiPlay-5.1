# Phase 3D.2T - Lockdown TLS and session lifecycle static audit

## Scope and real 3D.2S evidence

Static source audit and local unit tests only. No USB/iPhone, Lockdown,
StartSession, TLS, StopSession, StartService, CarKit, iAP2, MFi, NCM,
AirPlay or CarPlay operations were run in this phase.

The E01 result below is user-reported from the real Phase3D.2S run, not
reproduced by this audit:

- Exactly one readable accepted PAIRED record passed preflight and matched
  the connected phone after `GetValue(UniqueDeviceID)`.
- USB05AC:12A8, active configuration5, scoped USBMUX claim, USBMUX version,
  setup07, fresh TCP62078 and QueryType all succeeded.
- One StartSession request was sent and a complete response received.
  SessionID was present/type-valid; EnableSessionSSL=true.
- TCP remained open; FIN/RST/EOF were false.
- Pair, ValidatePair, SetValue, identity generation, persistence writes, TLS,
  StartService, CarKit, iAP2, MFi, NCM, AirPlay and CarPlay counts were0.
- No StopSession was sent; `StopSession attempts=0`. The run explicitly
  reported `STARTSESSION SUCCEEDED — TLS REQUIRED — SESSION STOP NOT CONFIRMED`.

This proves that this E01 accepted the existing PAIRED record for modern
Lockdown session establishment. It does **not** prove a TLS handshake or
confirm that closing the pre-TLS connection ended the server-side session.

## A. TLS upgrade trace and identity material

### Exact existing DiPlay sequence

In [LockdownCarKitClient.openService](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt):

1. `host.connect(destinationPort=62078)` creates one
   [Iap2UsbMuxTcpConnection](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt).
2. A `LockdownPlistChannel` sends plaintext StartSession with Label, Request,
   HostID and SystemBUID.
3. It requires a boolean EnableSessionSSL and stops if false.
4. Immediately after a true response, `plaintext.detach()` transfers ownership
   of that exact stream to `TlsDuplexChannel.open`. There is no intervening
   plaintext Lockdown request or second TCP62078 connect.
5. `LockdownTlsEngineFactory.create` creates an `SSLEngine` in client mode.
   `TlsDuplexChannel.performHandshake` begins it; NEED_WRAP emits the client
   handshake bytes on that same stream. The iPhone therefore responds as TLS
   server. This is an implementation-supported same-connection upgrade.

The intended wire order is:

```text
one plaintext USBMUX TCP62078 stream
    -> QueryType / existing-record association / StartSession
    -> StartSession reply: EnableSessionSSL=true
    -> TLS client handshake on the same stream
    -> encrypted Lockdown request(s)
```

Repository code shows no extra plaintext Lockdown message required or sent
between the successful StartSession reply and TLS. It does not establish a
normative Apple statement that every server forbids optional plaintext
messages; DiPlay should send none in this interval.

### Pair-record fields

[LockdownTlsEngineFactory.create](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownTlsEngineFactory.kt)
directly loads only:

- `RootPrivateKey`: PEM-decodes PKCS#8 and creates an RSA private key.
- `RootCertificate`: parses X.509 and is installed as the certificate paired
  with that client private key.

The factory places the key entry in a new in-memory default-type Java
KeyStore, initializes `KeyManagerFactory("PKIX")`, then
`SSLContext("TLS")`. It reads copies from the already-loaded
[LockdownPairRecord](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPairRecord.kt);
the persistent encrypted per-device record is decoded by
[AndroidDiagnosticPairStore](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt).
This TLS factory does not generate a key or read the AndroidKeyStore alias.
The password is a source-embedded temporary char array, cleared in `finally`;
the PEM/DER copies it owns are also cleared.

`HostCertificate`, `HostPrivateKey`, `DeviceCertificate`,
`DevicePublicKey`, and EscrowBag are **not directly read by the TLS engine
factory**. `DiagnosticPairMaterial.verify`, which accepted-record preflight
calls, separately parses/checks the paired root, host and device certificate
chain, verifies RSA keys/signatures and checks that DeviceCertificate public
key matches DevicePublicKey. This proves the locally stored material is
internally consistent; it does not validate the certificate actually
presented by the TLS peer.

EscrowBag is not consumed by `TlsDuplexChannel`, `LockdownTlsEngineFactory`,
or the shown Lockdown StartService request. This source trace does not prove
whether a separate later service may require escrow.

HostID/SystemBUID are sent in StartSession and are not handshake inputs to
the TLS engine. SessionID is retained as Lockdown-level session state for
StopSession; it is not added to TLS handshake parameters or TLS key
derivation by this code.

### Peer-certificate validation is a hardware-test blocker

The factory's private `UsbLockdownTrustManager.checkServerTrusted` is a
no-op and returns no accepted issuers. Consequently the engine accepts an
empty or otherwise untrusted server certificate chain. `getAcceptedIssuers`
does not pin the stored DeviceCertificate. The fact that DiagnosticPairMaterial
checks a stored certificate before StartSession is **not** peer-certificate
validation during TLS.

This is not public WebPKI validation, and not paired-device-certificate
validation. The code comment calls this behavior a match for the locked
transport. A corroborating pinned [libimobiledevice implementation](https://github.com/libimobiledevice/libimobiledevice/blob/fa0f79190142bc309307967c058f89c1b36eb6b8/src/idevice.c)
(`idevice_connection_enable_ssl`) presents RootCertificate and RootPrivateKey
as TLS client identity and calls `SSL_set_verify(..., 0, ...)`. That is
library-specific behavior, **not proof of an Apple protocol requirement or
permission to use a trust-all manager here**.

The task explicitly prohibits disabling certificate verification. Therefore
the current DiPlay TLS path is not acceptable for an authorized hardware
handshake until the Lockdown peer-authentication rule is established and
implemented as paired-device validation without trusting arbitrary peers.
Do not switch to public WebPKI or install another trust-all manager as a
compatibility workaround. The local characterization test demonstrates the
current manager accepts an empty peer chain; it does not endorse that policy.

## B. Android 5.1 / API22 API and protocol review

Files in the active Lockdown TLS path were checked for API availability and
post-22 calls:

| Use | API22 finding |
|---|---|
| `SSLContext.getInstance("TLS")`, `SSLEngine`, client mode, handshake status | Long-standing Java/Android JSSE APIs; present by API22. |
| `KeyStore.getDefaultType`, in-memory `load(null, ...)`, `setKeyEntry` | Long-standing JCA APIs. No AndroidKeyStore API is invoked by this TLS factory. |
| `KeyManagerFactory.getInstance("PKIX")` | [Android's API reference](https://developer.android.com/reference/javax/net/ssl/KeyManagerFactory) lists PKIX supported API levels1+. This confirms the named algorithm is documented for API22, not that the E01's particular JSSE provider completes the handshake. |
| RSA `KeyFactory`, `PKCS8EncodedKeySpec`, `CertificateFactory("X.509")`, `X509Certificate` | Long-standing JCA APIs; no newer parser or `java.time` API in the TLS path. |
| Base64 | Uses project `Base64Compat` backed by `android.util.Base64`, not `java.util.Base64`. |
| Endpoint identification | `SSLParameters.endpointIdentificationAlgorithm` is touched only in `Build.VERSION.SDK_INT >= N` (API24+); API22 skips the call. On newer releases it is set to null. This does not authenticate a Lockdown peer. |
| SNI and ALPN | No explicit SNI `SNIServerName`/server-name setup or ALPN application-protocol call is present. |
| Cipher suites | No explicit suite list or cipher requirement is configured; engine/provider defaults apply. |
| Protocol versions | `TlsDuplexChannel.open` intersects `TLSv1.2` and `TLSv1.3` with `engine.supportedProtocols`. Unsupported entries are removed; it errors if neither remains. API22 must negotiate from what its provider actually reports (TLS1.3 is not assumed). |

No use of `TrustManagerFactory` occurs; the code installs its custom
no-validation X509TrustManager directly. The APIs are largely available at
API22, but host Robolectric/JVM tests are not an Android5.1 provider or real
E01 certification. Before any TLS hardware test, verify API22 provider
behavior and paired-device certificate verification using tests/device
facts; do not lower protocol or certificate checks to make a handshake pass.

## C. Same TCP stream, sequence and buffering

[Iap2UsbMuxTcpConnection.send/recv](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2UsbMuxHost.kt)
keeps its TCP sequence and acknowledgement counters in the connection
object. It segments writes but increments the same sequence counter. TLS
writes use the original stream's `send`; there is no new connection object
or new source port at upgrade. Thus USBMUX/TCP sequence state continues
unchanged from plaintext Lockdown into TLS. A FIN/RST/EOF during handshake is
a transport failure, not an expected upgrade signal.

[LockdownPlistChannel](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownPlistChannel.kt)
reads the four-byte length and then exactly the declared XML byte count.
It has no BufferedInputStream or private socket read-ahead queue. Each
`recv` requests only the remaining byte count. The TCP connection's own
receive deque splits an oversized packet and retains any excess bytes.
`detach()` serializes after the current request and returns the exact
underlying stream. `TlsDuplexChannel` begins with its own empty encrypted
queue and obtains bytes from that same stream. Consequently queued bytes
after the Lockdown frame are not silently discarded. No TLS ServerHello can
be consumed by the plist XML parser as a result of this code's bounded reads.

`Iap2UsbMuxHost` runs its single USB reader/demultiplexer and routes packets
to the existing TCP object; TLS does not open another bulk reader. The
TLS channel has separate bounded encrypted/plaintext queues and serialized
engine/read/write access. This supports safe stream handoff at the source
level. Focused tests model same-object detach, frame-tail preservation,
client-initiated TLS bytes, malformed handshake, RST and EOF.

This is a static/source and injected-stream conclusion. It is not evidence
that the RST/FIN handling, provider behavior, peer certificate policy or
real hardware TLS handshake is fully validated.

## D. StopSession and session lifetime

The protocol reference in pinned libimobiledevice `src/lockdown.c`
(`lockdownd_start_session`, `lockdownd_stop_session`, client-free path) uses:

```text
Label=<client label>
Request=StopSession
SessionID=<SessionID returned by StartSession>
```

It sends StopSession on the same Lockdown property-list client. When
EnableSessionSSL was true, that client enables SSL before returning, so the
later StopSession travels inside the TLS channel; the implementation disables
SSL only after StopSession succeeds. StopSession requires the retained
SessionID. TLS close-notify is not the StopSession request.

DiPlay's current normal [LockdownCarKitClient](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt)
does not parse or retain SessionID and never sends StopSession. Its returned
service wrapper closes the service stream and then the TLS Lockdown stream.
`TlsDuplexChannel.close` attempts TLS close-notify and closes its owned TCP
stream; `Iap2UsbMuxTcpConnection.close` sends FIN/ACK when possible. Neither
operation proves that lockdownd ended its logical session. The manual
3D.2S diagnostic intentionally closed pre-TLS without sending StopSession;
whether that E01 reaped its session immediately is unknown.

Source confirms that a future successful TLS test can send one bounded
StopSession over that same TLS-protected Lockdown stream before closing.
It does **not** prove what the phone does after TLS handshake failure or
abrupt USB/TCP close, nor whether a subsequent StartSession on a fresh
connection is accepted after the 3D.2S unconfirmed session. Do not silently
retry, Pair, ValidatePair or claim that close rolled back the prior session.
A future test must capture a new SessionID and explicitly stop that session
after TLS success; abort/failure paths must report session cleanup unconfirmed.

## E. ValidatePair and PAIRED state

The previous ValidatePair hardware request fully wrote before peer TCP RST.
The new, distinct run with the same accepted record succeeded at StartSession
without sending ValidatePair and without RST. Together with pinned maintained
libimobiledevice and pymobiledevice3 code (modern iOS7+ path skips the legacy
ValidatePair verb and proceeds to StartSession), this supports:

- Explicit ValidatePair is not required by these modern client flows.
- E01's successful StartSession response is direct evidence it accepted the
  existing identity for session establishment. It is not evidence that a
  separate ValidatePair succeeded or that every future service is authorized.
- DiPlay should not require or retry explicit ValidatePair before a modern
  StartSession when the accepted record is verified and associated.
- No later normal DiPlay path checks the local diagnostic PAIRED-versus-
  VALIDATED enum: `CarPlayController.loadPairRecord` supplies a
  `LockdownPairRecord`; `LockdownCarKitClient` consumes it without that enum.

Do not rewrite PAIRED to VALIDATED to reflect StartSession success. The
label denotes which diagnostic request completed, not a proven Lockdown
requirement for StartSession or service startup.

## F. StartService boundary (not executed)

After TLS handshake, [LockdownCarKitClient.openService](../shared/src/main/java/com/shilapi/xcertplay/transport/LockdownCarKitClient.kt)
sends `Request=StartService` and the chosen Service over the **TLS-upgraded
Lockdown connection**.

- `open(pairRecord,label)` selects exactly `com.apple.carkit.service`.
- `CarPlayController.runStack` reaches this call in its
  `CarPlayStatus.ConnectingControl` phase.
- The current controller also has a separate temporary lab
  `com.apple.syslog_relay` open earlier in that routine. Neither was run in
  Phase T.
- The response must contain a valid Port. `EnableServiceSSL` is optional and
  currently defaults false if absent.
- DiPlay opens a **new USBMUX TCP connection** to the returned service port.
  It starts another TLS channel on that service TCP only if
  EnableServiceSSL=true; otherwise it uses the raw service stream.
- The `com.apple.carkit.service` selection is passed from controller to
  `LockdownCarKitClient.open`; StartService is where Lockdown authorizes and
  returns the CarKit service endpoint. The subsequent CarKit stream precedes
  wired iAP2 logic, outside the proposed U boundary.

No StartService, service connection, CarKit, iAP2, MFi, NCM, AirPlay or
CarPlay request/activation occurred in this phase.

## G. Future Phase 3D.2U design gate

A no-service TLS handshake test is **not authorized/design-ready for hardware**
while the current arbitrary-peer trust manager remains. First resolve and
test a paired-device certificate policy consistent with the task's
no-trust-all/no-weaken requirement, and establish API22 provider behavior.
The existing reference implementation's no-peer-verification policy is not
sufficient security justification.

After that gate, the narrow test may be designed as:

```text
existing exactly-one accepted record
-> already-proven USB/config5/USBMUX
-> fresh TCP62078
-> QueryType; GetValue association
-> StartSession once
-> require correlated success + valid SessionID + SSL=true
-> TLS client handshake on the SAME stream/sequence state
-> require validated paired-device peer identity and completed handshake
-> optionally ONE allowlisted encrypted harmless Lockdown query
-> StopSession once over TLS; verify correlated success
-> close TLS, release USBMUX, close USB; STOP
```

There is no Pair/ValidatePair/SetValue/fallback, identity generation,
record write, service, CarKit, iAP2, MFi, NCM, AirPlay or CarPlay. The test
must report booleans/safe status only: certificate/private-key/device-
certificate present, materialReadable, negotiated TLS protocol name,
handshake attempted/succeeded, StopSession succeeded and transport flags.
Never log or export certificate/private-key bytes, identities, SessionID,
EscrowBag, record/XML contents, TLS secrets or arbitrary exception details.

If TLS or certificate verification fails, close and report session cleanup
unconfirmed; do not send plaintext StopSession, reconnect, re-pair or proceed
to any service.

## H. Tests and verification

Added
[LockdownTlsBoundaryAuditTest](../shared/src/test/java/com/shilapi/xcertplay/transport/LockdownTlsBoundaryAuditTest.kt),
five local-only tests:

1. Valid existing record material passes integrity checks and builds a
   client-mode TLS engine without modifying/regenerating the record.
2. Lockdown plist parsing consumes the exact declared response and preserves
   tail bytes on the same stream for handoff.
3. TLS handshake initiation writes a ClientHello to that stream; malformed
   response fails and closes it.
4. Injected reset and EOF stop handshake and close the stream.
5. Current trust manager accepts an empty server certificate chain, making
   the peer-verification gap executable and explicit.

The other requested boundaries are backed by production path inspection and
existing focused Phase3D.2S tests: exact StartSession/SSL parsing, PAIRED and
VALIDATED records, no Pair/ValidatePair/fallback/mutation, conditional
StopSession only for explicit plaintext SSL=false, no TLS/services on that
diagnostic, and cleanup on errors. No test sent USB/network traffic.

Command:

```powershell
.\gradlew.bat :shared:testDebugUnitTest `
  --tests com.shilapi.xcertplay.transport.LockdownTlsBoundaryAuditTest
```

Result: **5 passed, 0 failures** (Robolectric SDK28 on the host JVM).
An initial attempt to select Robolectric SDK22 could not run because
Robolectric4.17 reports API22 unavailable; it was changed to supported
SDK28. Therefore this is not an Android5.1 TLS-provider runtime test.
Static Android API review: no unguarded post-22 Java/Android API was found
in the TLS path, but peer validation/provider compatibility remains a gate.
minSdk22 and `LegacyLaunchBuild.CONNECTIONS_ENABLED=false` remain unchanged.

LOCKDOWN TLS PATH REQUIRES API22/STREAM FIXES BEFORE HARDWARE TEST

SESSION CLEANUP REQUIRES MORE STATIC ANALYSIS
