# Phase 3D.2W - controlled CarKit service TCP/TLS connection

Phase 3D.2W has completed its real-car test successfully: CarKit TCP/TLS and peer validation, encrypted Lockdown StopSession, USB release, and cleanup all passed. The report also contained a post-success retired-Lockdown RST diagnostic during shutdown. `minSdk=22`; production connections remain disabled.

## Architecture and boundaries

The USBMUX host has one serialized USB writer and one reader which dispatches inbound TCP frames by the client source port to independent connection state (TCP sequence numbers, receive buffers, and close state). Strict diagnostic mode permits the Lockdown socket and one additional service port explicitly authorized from a validated successful CarKit StartService response. Each incoming packet must match both the client socket and its expected remote port. The service socket has no application-data write surface.

After a strict-diagnostic TCP socket closes, its source-port mapping is retained as a bounded closing-socket tombstone until the USBMUX host closes. This handles delayed ACK, FIN, and RST control frames without misrouting them to Lockdown or treating them as unknown sockets. For TCP RST, segment data is ignored by reset processing, not delivered to the byte stream. The corrected path discards payload only when flags are exactly RST, the socket is the retired authorized service connection, its peer/local mapping already matched, and sequence and acknowledgment validity pass. It records `closingSocketRSTPayloadFrames` and `closingSocketRSTPayloadBytes`. Other retired-socket payload, malformed flags, a wrong peer port, invalid sequence/acknowledgment state, or an unknown source port still fails the shared transport. Local FIN advances the TCP sequence number. Existing `closingSocketACK`, `closingSocketFIN`, and `closingSocketRST` counts remain.

The instrumented report identified `CLOSED_SOCKET_PAYLOAD` on `RETIRED_SERVICE` with `tcpFlags=RST|PAYLOAD`, payload length 56, and both sequence/acknowledgment validity booleans true. The prior handler rejected any payload before checking RST, even though reset data is discarded rather than delivered. This correction is deliberately limited to that authenticated-by-socket-state reset case; it does not relax active-socket validation or accept data on the retired byte stream. No routing, Lockdown TLS, peer certificate validation, or StopSession request behavior changes.

### Post-success Lockdown RST observation

The subsequent report line was `CLOSED_SOCKET_RST_PAYLOAD_UNAUTHORIZED; socketRole=RETIRED_LOCKDOWN; tcpFlags=RST|PAYLOAD; payloadLength=56; stopSessionStage=AFTER_STOPSESSION_RESPONSE; hostState=CLOSED; hostFailure=NONE; Cleanup=true`. This is distinct from the retired-service RST correction above and is not evidence that StopSession failed.

`AndroidReadOnlyLockdownAccess.tlsSessionRequest()` sets `stopSessionResponseReceived=true` only after the encrypted Lockdown request returns a parsed response. The W controller then reports StopSession confirmed. Its `finally` release path runs `stopTransport()`, which closes the Lockdown TLS channel, closes the Lockdown TCP socket, then closes the USBMUX host. The host close sets `closed=true` before closing the pipe and joining the reader. A frame already dequeued by the reader can therefore reach `handleClosingPacket()` after that transition.

The reported reason is the final `else` in the retired-socket RST-with-payload validation: it means the flags were exactly RST (not RST|ACK), the socket was the retired Lockdown socket rather than the authorized service socket, and the sequence/acknowledgment checks passed. The handler emits the diagnostic and throws a Protocol exception; the reader calls `fail()`, which immediately returns because the shared host is already closed. That is why the report shows `hostFailure=NONE` and `Cleanup=true`. TCP reset processing discards the segment data; it is not passed to TLS or Lockdown. The 56-byte payload was not inspected, so no claim is made about its contents.

This is a queued/in-flight terminal RST observed during already-successful USBMUX shutdown, not a live transport failure. No exception for retired Lockdown sockets is added: unlike the service reset case, the current evidence shows StopSession already completed and the host was closing. No further hardware test is required for this report; do not rerun W solely to remove this post-close diagnostic.

Lockdown TCP/TLS remains open while the second USBMUX TCP socket is established and the TLS handshake is attempted. The service TLS uses the same existing pairing record credentials and `LockdownPeerCertificateValidator` pin. No certificate-verification fallback is available. After the handshake, service TLS and TCP are closed first; encrypted StopSession is then sent on the still-open Lockdown TLS stream. Finally Lockdown TLS/TCP, USBMUX host, interface, and USB handle are closed. A shared USBMUX transport failure can still close both logical sockets; the diagnostic records that and makes a best-effort StopSession attempt.

Only TLS handshake records are exchanged on the service socket. No service application bytes are sent. Pair, ValidatePair, SetValue, identity regeneration, pairing-record writes, retries, reconnects, CarKit commands, iAP2, MFi, NCM, AirPlay, and CarPlay are excluded.

## Single-run hardware procedure

Perform only as a separately authorized manual action. This procedure does not authorize repeating pairing or USB-configuration phases.

1. Install the corrected debug APK in place: `0.2.12-api22-phase3d2w-retired-rst-payload-fix`. Preserve the current installation data, KeyStore, and existing pairing record; do not uninstall, clear data, regenerate identity, or delete/overwrite a record.
2. Use the same API22 head unit and iPhone state as the successful 3D.2V run. Retain existing USB permission and active configuration 5. Do not change USB mode or approve a Trust prompt.
3. Open Settings/Diagnostics and select **Phase 3D.2W — Controlled CarKit service TCP/TLS connection**. Confirm exactly one readable accepted `PAIRED` record; do not proceed with a missing, ambiguous, unreadable, or `VALIDATED`-only record.
4. Press **Test CarKit service TLS (NO APP TRAFFIC)** once and confirm once. Do not double-tap, rerun, or reconnect.
5. Wait for cleanup and save the report. Verify one StartSession, one Lockdown TLS handshake, one StartService, one service TCP connect, at most one service TLS handshake, zero application payloads, service TLS/TCP close status, encrypted StopSession confirmation, and final Lockdown/USB cleanup status. A retired-Lockdown RST diagnostic with `stopSessionStage=AFTER_STOPSESSION_RESPONSE`, `hostState=CLOSED`, `hostFailure=NONE`, and `Cleanup=true` is a post-success shutdown observation as described above, not a reason to retry.
6. This is one diagnostic attempt only. If StopSession or cleanup is not confirmed, save the report and STOP; do not retry or reconnect. Never connect to the service port outside this diagnostic or send CarKit application traffic.

## Verification

Focused tests: `ModernStartSessionDiagnosticTest`, `ModernStartSessionUiTest`, and `Iap2UsbMuxMultiplexTest`, plus existing Lockdown TLS validation tests. APK version at the time of the successful hardware run: `0.2.12-api22-phase3d2w-retired-rst-payload-fix`.
