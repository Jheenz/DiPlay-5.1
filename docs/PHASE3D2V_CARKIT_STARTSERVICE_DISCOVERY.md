# Phase 3D.2V - controlled CarKit StartService discovery

Implementation and unit-test phase only. **No hardware test was performed.** The confirmed Phase 3D.2U hardware success is the baseline; this diagnostic does not change the production connection flags. `minSdk=22`, `LegacyLaunchBuild.CONNECTIONS_ENABLED=false`.

## Scope and safeguards

The action is available only as a separate, manually triggered and explicitly confirmed Settings/Diagnostics test. It requires exactly one readable existing `PAIRED` record, the supported Apple USB device with existing USB permission, active configuration 5, and its scoped USBMUX interface. A `VALIDATED` record is not accepted for this phase.

It uses the existing USBMUX initialization and fresh TCP62078 Lockdown transport, checks Lockdown QueryType and the stored-record/device association, sends StartSession once, and requires `EnableSessionSSL=true` plus a valid SessionID. TLS is established on that same TCP stream and the peer key is pinned to the paired device certificate. It then sends exactly one encrypted `StartService` for `com.apple.carkit.service`.

The `GetValue(UniqueDeviceID)` response must still echo `Request=GetValue`, have no Lockdown `Error`, have a successful/absent `Result`, and contain a valid identity that exactly matches the accepted stored record. Additional response metadata is ignored and reported as a count; only the benign field names `Domain` and `Key` may be named in the report. All values and other field names remain redacted. This preserves identity/record verification while allowing Lockdown response extensions. `StartSession` response fields remain strict.

The response report contains only safe status: error present/absent and a whitelisted safe error classification, whether the service port is valid, and `EnableServiceSSL=true/false/UNKNOWN`. The port value and identifiers are not reported, and the port is never contacted. No service connection, CarKit traffic, iAP2, MFi, NCM, AirPlay, or CarPlay is initiated. One encrypted StopSession using the active SessionID is attempted after the service response and must be confirmed; a StopSession failure or resource cleanup failure withholds success.

There is no Pair, ValidatePair, SetValue, identity generation, record write, retry, reconnect, or TLS downgrade. If TLS validation fails, no StartService or StopSession is sent because an authenticated TLS channel was not established. If StartService returns an error, an invalid/malformed response, or a request-level failure while TLS remains usable, the diagnostic still attempts StopSession once. No response or report is interpreted as CarPlay readiness.

## Single-run hardware procedure

Perform this only as a separate, explicitly authorized hardware test after reviewing the current report and preserving the existing installation and pairing data. This document does not authorize repeating earlier pairing or configuration phases.

1. Install the corrected debug APK in place, retaining the current app data, KeyStore, and pairing record. Do not uninstall, clear app data, regenerate identity, or delete/overwrite the record. The corrected APK reports `0.2.12-api22-phase3d2v-association-fix`.
2. Keep the iPhone connected as during the prior attempt. Confirm that the prior diagnostic reported `Cleanup=true`; if it did not, STOP without another run. Do not change USB mode, request permission, or approve a Trust prompt.
3. Open DiPlay Settings/Diagnostics and locate **Phase 3D.2V — Controlled CarKit StartService discovery**. Confirm that one readable existing `PAIRED` record is present; do not proceed with a missing, ambiguous, unreadable, or merely `VALIDATED` record.
4. Read the warning. Press **Discover CarKit service (NO CONNECTION)** once, then press the positive confirmation button once. Do not double-tap, rerun, or reconnect.
5. Wait for the diagnostic to finish. Save the report. A successful run must show one StartSession, one TLS handshake, one StartService, one confirmed StopSession, and `Cleanup=true`. The response status may report an unavailable/error response; that is not discovery success, but StopSession and cleanup must still be assessed.
6. Do not connect to a reported service port or start any CarKit/iAP2/MFi/NCM/AirPlay/CarPlay traffic. If TLS validation, session cleanup, or USB cleanup is not confirmed, save the report and STOP. Do not retry or repair pairing as part of this test.

## Validation performed by implementation

Focused tests: `ModernStartSessionDiagnosticTest` and `ModernStartSessionUiTest`. The hardware procedure above has not been run. Debug APK identity: `0.2.12-api22-phase3d2v-carkit-discovery`.
