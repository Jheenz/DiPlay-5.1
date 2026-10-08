# Phase 3D.2T.1 - Lockdown TLS peer validation and session cleanup

Static/unit-test phase only. No real USB hardware was run during development.
Gates unchanged: `LegacyLaunchBuild.CONNECTIONS_ENABLED=false`, `minSdk=22`.
Version: `0.2.12-api22-phase3d2u-lockdown-tls`.

## 1. Starting evidence

Real E01 Phase 3D.2S (existing accepted PAIRED record, no Pair/ValidatePair):
config5 USBMUX -> fresh TCP62078 -> QueryType -> association VERIFIED ->
StartSession once -> valid SessionID, `EnableSessionSSL=true`, TCP open (no FIN/RST/EOF),
StopSession attempts=0.

Phase 3D.2T audit blockers: `LockdownTlsEngineFactory` installed a TrustManager that
accepted any (including empty) server chain, and cleanup after TLS was unproven.

## 2. Previous trust-all defect (removed)

The former `checkServerTrusted` body was empty and `acceptedIssuers` was empty, so any TLS
peer - or no certificate at all - was accepted. That class no longer exists. The
regression test `lockdownTrustManagerRejectsAnEmptyPeerCertificateChain` and
`LockdownTlsValidationCleanupTest` fail if a trust-all manager reappears.

## 3. Replacement validation model

`LockdownPeerCertificateValidator` (shared/transport) pins the live TLS peer to the paired
device key:

- Expected identity = the `DeviceCertificate` already in the accepted pair record. DiPlay issued
  that certificate at Pair time over the iPhone's own `DevicePublicKey` (Lockdown GetValue),
  so its SubjectPublicKeyInfo is the iPhone's paired key.
- The peer leaf certificate's SubjectPublicKeyInfo must be byte-equal
  (`MessageDigest.isEqual`) to the expected SPKI. The TLS handshake (CertificateVerify / key
  exchange) proves the peer holds the corresponding private key.
- Rejections, each failing the handshake before any application data:
  `EXPECTED_DEVICE_CERTIFICATE_MISSING`, `EMPTY_PEER_CHAIN`, `MALFORMED_PEER_CERTIFICATE`,
  `PEER_KEY_MISMATCH`. Only `PAIRED_DEVICE_KEY` passes.
- No Web PKI, hostname, or validity-date check. These are not part of the Lockdown pairing
  model, and the head-unit clock is unreliable. They are not used as substitutes for key pinning.
- `PinnedTrustManager.checkClientTrusted` always throws, and `acceptedIssuers` is empty.
- `TlsDuplexChannel.open` re-validates the negotiated session's peer chain after the handshake
  (defence in depth). A failure closes the stream.
- Safe metadata only: `expectedDeviceCertificatePresent`, `peerCertificatePresent`,
  `peerCertificateCount`, `peerCertificateMatch`, `certificateValidation=PASS/FAIL`. No
  certificate, fingerprint, key, HostID, SystemBUID, EscrowBag, UDID or SessionID is logged.

**Hardware uncertainty (documented honestly).** No reference client verifies the Lockdown
server. libimobiledevice uses `SSL_VERIFY_NONE`, and pymobiledevice3 uses `CERT_NONE`. That
the iPhone presents a leaf over its paired `DevicePublicKey` is the expected protocol
behaviour, but it is not yet hardware-proven. If the phone presents a different key, 3D.2U
fails closed with `LOCKDOWN TLS PEER VALIDATION FAILURE`: no encrypted request, no
StopSession, and the session is left not stopped, which is the same state 3D.2S already
produced. Security is never weakened to make it pass.

## 4. Client certificate / private key

`LockdownTlsEngineFactory.create(record, validator)` builds the client KeyManager only
from the record's existing `RootCertificate` + `RootPrivateKey`. This matches
libimobiledevice's lockdown TLS client identity. (The "host certificate" wording in the
request corresponds to this stored root identity. The host certificate has no separate
role in the TLS client handshake.)

- No generation, no Pair, no SetValue, no persistence writes.
- Missing or unreadable material throws before any ClientHello is written, and the stream
  is closed.
- Tests prove that the reused root certificate is the one presented to the server, and that
  the record is unchanged afterwards.

## 5. API22 compatibility (production path)

Used: `SSLContext.getInstance("TLS")`, `SSLEngine`, `KeyStore.getDefaultType()`,
`KeyManagerFactory("PKIX")`, `CertificateFactory("X.509")`, `KeyFactory` (RSA PKCS#8),
`X509Certificate.publicKey.encoded`, `MessageDigest.isEqual`. All are available since
API 1. Android 5.1 enables TLSv1.2 by default for client SSLEngines.

- `SSLParameters.endpointIdentificationAlgorithm` is guarded at `SDK_INT >= N`. It is
  not used for authentication.
- No SNI, no ALPN, no `java.util.Base64` (`Base64Compat`), no `java.time`.
- Protocols are the intersection of {TLSv1.2, TLSv1.3} with what the engine supports. If the
  intersection is empty, the handshake is refused rather than downgraded. Default cipher suites
  are used; none are weakened.
- If Android 5.1's provider cannot negotiate with the iPhone, the result is
  `LOCKDOWN TLS HANDSHAKE FAILURE` and STOP. There is no fallback.

## 6. Same-stream TLS handoff and buffering

`AndroidReadOnlyLockdownAccess.startSessionTls`:

- The plaintext StartSession goes on the fresh TCP62078 stream.
- `LockdownPlistChannel.detach()` returns that same stream, and identity is checked with `=== tcp`.
- TLS opens on it. No second SYN and no new 62078 connection.
- The plist reader reads exact length-prefixed counts. Bytes beyond the StartSession response
  stay in the USBMUX TCP push-back buffer and are delivered to the TLS engine once, in order.
- Tested with 1-byte fragmentation and with the ServerHello coalesced into the StartSession segment.
- After TLS the allowlist permits only one encrypted QueryType and one encrypted StopSession
  whose SessionID equals the active one. Plaintext requests are rejected.
- With `EnableSessionSSL=false`, TLS is never substituted.

## 7. StopSession

Sequence for `SessionID=<valid>`, `EnableSessionSSL=true`:

TLS established -> encrypted QueryType -> encrypted
`{Label, Request=StopSession, SessionID}` -> verify response (Request echo matches, no
`Error`, `Result` absent or `Success`) -> close TLS -> close USBMUX TCP -> release
interface -> close USB.

- Never sent in plaintext after SSL is enabled.
- TCP close is never reported as a confirmed stop.
- A StopSession failure is reported as `STOPSESSION NOT CONFIRMED`. Local cleanup still runs,
  with no retry, no new StartSession and no Pair.
- Handshake or peer-validation failures send no StopSession; that would require an
  authenticated channel.

## 8. Previous 3D.2S session

**UNKNOWN.** No source or protocol evidence proves that the device invalidates the old
SessionID when the transport closes. libimobiledevice and pymobiledevice3 both send
StopSession explicitly and do not document what closure does. The 3D.2S hardware run
shows only that a fresh connection accepted a new StartSession. Nothing is reset, and no
speculative StopSession is sent for the old SessionID.

## 9. ValidatePair

Excluded from this path. The stored record stays `PAIRED`, and nothing relabels it `VALIDATED`. No Pair fallback.

## 10. Phase 3D.2U - implemented as a manual diagnostic only

Settings/Diagnostics: **Phase 3D.2U — Controlled Lockdown TLS round trip**, button
**Test Lockdown TLS round trip (NO SERVICES)**, with a confirmation dialog. Nothing runs automatically.
The report is included in `Save diagnostic report`.

Sequence: one verified accepted record -> already-transitioned config5 USBMUX (no mode
preparation, no permission request) -> fresh TCP62078 -> QueryType -> association ->
StartSession once -> requires a valid SessionID and `EnableSessionSSL=true` -> TLS on the
same stream with the pinned peer -> one encrypted QueryType -> encrypted StopSession ->
cleanup -> STOP. No retries.

Classifications: `LOCKDOWN TLS ROUND TRIP CONFIRMED — STOPSESSION CONFIRMED`,
`LOCKDOWN TLS HANDSHAKE FAILURE`, `LOCKDOWN TLS PEER VALIDATION FAILURE`,
`ENCRYPTED LOCKDOWN REQUEST FAILURE`, `STOPSESSION NOT CONFIRMED`, plus the 3D.2S
StartSession/association classifications.

Counters line: `Pair=0 ValidatePair=0 SetValue=0 identityGeneration=0 persistenceWrites=0
StartSession=1 TLSHandshake<=1 StartService=0 CarKit=0 iAP2=0 MFi=0 NCM=0 AirPlay=0 CarPlay=0`.

## 11. Tests

- `LockdownTlsValidationCleanupTest` (shared): a real JVM TLS server presents the paired
  device certificate/key and requires a client certificate. Covers:
  - accept, wrong key, empty chain, malformed chain, and missing expected/host certificate/key;
  - reuse of the root identity without mutation;
  - same stream, with fragmented and coalesced boundaries;
  - RST, EOF and garbage during the handshake;
  - the encrypted round trip, and StopSession inside TLS with the correct SessionID;
  - StopSession failure followed by cleanup;
  - no trust-all.
- `LockdownTlsBoundaryAuditTest` (empty-chain rejection).
- `ModernStartSessionDiagnosticTest`:
  - TLS ordering; SSL=false not substituted;
  - each failure class cleans up; StopSession only after authenticated TLS;
  - StartService/CarKit/iAP2/MFi/NCM/AirPlay/CarPlay remain zero.
- `AndroidModernStartSessionAccessTest`, `AndroidReadOnlyLockdownAccessTest`: real-access
  TLS on the fake USBMUX. One SYN, peer RST/FIN after ClientHello, cleanup, and no
  requestPermission.
- `ModernStartSessionUiTest`: the 3D.2U button is manual-only and a missing record stops before USB.

All of the above pass. The full-suite failures in unrelated classes (BYD/SystemBar/Location/
Hotspot settings, `UsbDeviceFilterTest`, `Phase3ADeviceSettingsTest`,
`DiLink3ClusterRecoveryTest`) are pre-existing at HEAD and untouched by this phase.

`:mobile:assembleDebug` succeeds. The APK reports `minSdkVersion=22` and versionName
`0.2.12-api22-phase3d2u-lockdown-tls`. Lint (`:shared:lintDebug`, `:common:lintDebug`)
reports no NewApi findings in any file on the TLS path. The pre-existing NewApi findings
elsewhere are outside this phase.

## Verdict

TLS PEER VALIDATION AND SESSION CLEANUP FIXED — COMBINED 3D.2U HARDWARE TEST READY
