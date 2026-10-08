# Phase 3D.2P.3 - Existing accepted-record lookup and independent reopen

## Outcome and corrected chronology

The supplied current E01 metadata proves one readable/decryptable PAIRED
record, matching local association key and present storage alias. It does
not by itself prove that the currently connected iPhone matches the record.

During this phase the user explicitly confirmed:

> The NOT_FOUND report was the earlier uninstall/reinstall run; P.1 has not
> been rerun after the latest Pair.

Therefore these are **different storage states at different times**:

1. Earlier accepted record, then uninstall/reinstall.
2. P.1 empty local inventory -> PAIR_RECORD_PREFLIGHT/PAIR_RECORD_NOT_FOUND.
3. Subsequent controlled Pair recreated an accepted PAIRED record.
4. P.2 now observes that current record.

The premise that P.1 falsely missed the currently observed record is not
established. Uninstall explains the earlier absence, and a later successful
save explains current presence. No reproducible same-state false NOT_FOUND
or unavailable-association-to-NOT_FOUND translation was found.

Under Part D's conditional requirement, **no production lookup fix was made**.
This avoids changing transport/persistence or weakening association checks
to fix a bug not demonstrated by the corrected evidence. Only local software
tests and this documentation were added. No APK/version change or build,
real USB/device/phone operation, identity regeneration, migration or repair.

## 1. Exact source comparison

Line numbers refer to source inspected for this phase.

| Surface | Exact source and relevant lines | Behavior |
|---|---|---|
| P.1 action/store construction | [DiPlayActivity](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt), lines173, 1607 | Null-by-default test seam; production constructs AndroidDiagnosticPairStore(applicationContext). |
| Local-only action/store construction | Same class, lines184, 1559 | Separate null-by-default test seam; production constructs the same AndroidDiagnosticPairStore(applicationContext). |
| Store backend | [AndroidDiagnosticPairStore](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt), lines38-40 | Both use MODE_PRIVATE SharedPreferences `diplay_controlled_pair`. Conventional file: `<applicationInfo.dataDir>/shared_prefs/diplay_controlled_pair.xml`. |
| Metadata scan | Same class, inspect lines42-67 and inspectRecord lines69-98 | Enumerates all `device_` entries, decrypts individually, reports state/association/material status; failed entries remain explicit. |
| P.1 accepted inventory | Same class, acceptedRecords lines123-140 | Enumerates the same prefix, decrypts/decodes, verifies embedded association/key and material, skips PREPARED, returns accepted candidates or throws. No caller-supplied identifier. |
| Exact association lookup | Same class, load lines110-121; name lines175-179 | `device_` + lowercase SHA-256 of validated UniqueDeviceID UTF-8 bytes. Decrypt using entry-name AAD and check embedded device association. |
| Local gate -> USB order | [ControlledPairDiagnostic.run](../common/src/main/java/com/shilapi/xcertplay/ControlledPairDiagnostic.kt), lines92-105, 112 | acceptedRecords before access.open; empty returned list alone triggers preflight NOT_FOUND at line95. |
| Phone association timing | [ControlledLockdownPairing.run](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledLockdownPairing.kt), lines52-60 | After service discovery, GetValue(UniqueDeviceID), validate it, then store.load(actual identifier). Missing match stops in existingOnly mode. |
| Existing-only request guard | Same class, exchange line148 | Allows only GetValue and ValidatePair, not Pair or SetValue. |

### What P.1 does not require before USB

P.1 does not ask load() to hash an unavailable UDID or a USB descriptor.
It validates **stored** associations internally using the decrypted candidate.
That is possible before connecting and is not proof that the attached phone
matches. The actual phone identifier is obtained only later.

P.2 also checks that the decrypted association hashes to its entry name.
Thus P.2's association matches=true means **local record/key consistency**,
not live phone association. Neither path substitutes a serial, USB name,
HostID or SystemBUID for UniqueDeviceID.

There is no branch mapping "no current phone identifier" to
PAIR_RECORD_PREFLIGHT/PAIR_RECORD_NOT_FOUND. A missing/invalid GetValue value
stops at DEVICE_ASSOCIATION/INVALID_DEVICE_IDENTIFIER; a valid identifier
with no corresponding local entry stops later at LOAD_CANDIDATE/NOT_FOUND.
These are distinct stages.

### Relevant differences between the readers

- P.2 reports incomplete entries individually. P.1 fails closed if any
  recognized entry cannot decode/verify, even alongside a valid one.
- P.1 rejects a legacy `xcertplay_airplay`/`lockdown_host_id` marker
  (rejectLegacy lines169-173); local metadata does not use that rejection.
  A readable local record plus that marker could make preflight INVALID,
  **not NOT_FOUND**. A focused test now confirms this distinction.
- PREPARED-only inventory produces PAIR_RECORD_PREPARED, not NOT_FOUND.
- KeyStore/decrypt/association/material errors become PAIR_RECORD_INVALID,
  not an empty accepted list. Empty recognized inventory returns an empty
  list and produces NOT_FOUND.

With one readable PAIRED/VALIDATED entry, no legacy marker and unchanged
context/data, both source readers discover it. Tests now demonstrate this
using the real Android store, not a custom acceptedRecords list.

## 2. Package, alias, update and cleanup

Both actions execute in the same installed app/application context and use
the same alias `diplay-controlled-pair-storage-v1`. Neither changes package,
user, signing identity, storage name or format. P.2 reads presence and
decryptability; P.1 uses that protected store. Wrong/missing wrapping keys
would fail decryption rather than legitimately produce empty inventory.

The earlier uninstall can remove app-private data and KeyStore-backed state.
No signing/package mismatch or data-clearing update has been established
between the latest Pair and current inspection. The P.2 APK signer matched
the previously inspected P.1 APK; current installation identity was not
independently interrogated during development. Preserve the installation and
use same-package/same-signer in-place updates only.

Persistence and cleanup:

- AndroidDiagnosticPairStore.save lines142-167 synchronously commits and
  decrypt/readback-compares the complete encrypted entry. Downgrade and
  identity/material replacement are refused (lines146-152).
- ControlledLockdownPairing lines112-117 saves PAIRED before validation;
  lines121-123 save VALIDATED only after validation succeeds.
- Its catches lines126-138 wrap/rethrow without save/delete/rollback.
- ControlledPairDiagnostic finally lines144-167 releases/closes only
  transport resources; there is no record or alias cleanup operation.
- [Android Session](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt)
  stopTransport/releaseUsbMux/close close channel/host/pipe/USB resources.
  They do not receive a store or modify preferences.

No path here deletes/downgrades an accepted record, clears association,
removes aliases, regenerates identity, imports records or repairs storage.
The earlier and current results do not demonstrate such a path.

## 3. Existing order and proposed stricter policy

Current order (unchanged):

```text
enumerate and locally verify accepted inventory, no live identifier needed
-> exact USB/config5/USBMUX/Lockdown discovery guards
-> QueryType -> GetValue(UniqueDeviceID)
-> exact accepted-record load and verification
-> existing diagnostic's one ValidatePair
-> save VALIDATED only on success -> cleanup -> STOP
```

This phase does not execute that hardware flow or authorize its validation
step. The reset's cause remains unresolved.

For a future explicitly approved association/validation phase, the safe
conceptual order is:

```text
local protected inventory check
-> require readable accepted state and intact association/material
-> refuse empty, PREPARED-only, corrupt/undecryptable or legacy-blocked state
-> if strict single-candidate policy is required, refuse multiple candidates
-> only then existing discovery and actual identifier acquisition
-> match actual identifier to protected record
-> missing/invalid/nonmatching association: STOP, no writes or Pair
-> matching record is eligible for a separately authorized validation test
```

No automatic Pair fallback, SetValue, identity/key generation, storage repair,
record migration/deletion or reconnect.

**Multiple-record policy is not a proven NOT_FOUND defect.** Current preflight
allows multiple valid accepted entries but does not select one by position;
later exact identifier lookup disambiguates. If identity is unavailable, it
stops without selecting, validating or writing. The user's proposed
single-record-before-USB rule is stricter than existing behavior. It is not
implemented here because Part D permits a production lookup fix only with
a proven false-NOT_FOUND root cause. Tests characterize the existing policy;
they do not claim a new multiple-record pre-USB rejection exists.

No corrected production lookup order is claimed: the inventory-before-live-
association order already exists. Any future policy change must be explicit,
not presented as an explanation of the old uninstall-related report.

## 4. Independent disk reopen test

New [ExistingPairLookupReopenTest](../common/src/test/java/com/shilapi/xcertplay/ExistingPairLookupReopenTest.kt):

1. Save a synthetic accepted candidate through AndroidDiagnosticPairStore's
   normal encrypted save/commit path, with writing-store references scoped
   to that operation.
2. Require the actual preferences XML file to exist.
3. Construct a **new Android SharedPreferencesImpl from that file**, bypassing
   the Context preferences cache; assert it is not the previous instance.
4. Create a fresh application-context wrapper and AndroidDiagnosticPairStore
   pointing to that independent preferences instance.
5. Discover accepted records, load/decrypt the exact association and inspect
   metadata. Require PAIRED/VALIDATED state, READABLE status and matching
   association; assert no preferences changes during reads.

This is not just two repository wrappers around one cached preferences object.
It verifies independent file-backed preferences/store reopen in Robolectric
SDK28, with the original repository not consulted. Only fixture wrapping-key
service access is retained, modeling a surviving KeyStore. Reflection into
SharedPreferencesImpl is **test-only**, not a production API22 dependency.

Another test uses the independently reopened protected store with a synthetic
identifier reply and injected validation reset, forbids store.save/systemBuid
and generation, then independently reopens again and requires PAIRED still
discoverable. All protocol callbacks are local fakes, not USB/phone requests.

Limits: this does not launch a second app process, power-cycle E01, or verify
real API22 KeyStore persistence. It proves independent store/file reopen for
the controlled PC fixture. The current E01 metadata separately proves a
readable protected accepted record at the reported observation time.
No real credentials or identifiers are printed/exported by these tests.

## 5. Coverage and safety

New tests cover:

- One PAIRED and one VALIDATED candidate discovered by both readers after
  independent file reopen.
- Missing, PREPARED-only and corrupt entries rejected by actual preflight
  with zero USB opens and no generated identity metadata.
- Accepted inventory verified before any phone identifier; mock empty USB
  inventory prevents the test from entering any transport path.
- Multiple accepted entries enumerate without choosing a phone; exact load
  and unavailable-identifier STOP are distinct, with no validation fallback.
- Exact matching and nonmatching synthetic identifiers; invalid live identity
  stops before store lookup.
- Legacy marker results INVALID rather than falsely reporting NOT_FOUND.
- Injected failed validation retains PAIRED on independent reopen, with
  save/generation/forbidden request callbacks that throw if reached.

Existing focused suites additionally cover missing association vs physical
entry, wrong-key/metadata reporting, cleanup retention after mocked Pair/reset,
no Pair/SetValue fallback, one-shot bounds and excluded later stages.
Fixture cryptographic keys are test data; production lookup never generates
keys. No real USB/Pair/ValidatePair/SetValue/network/session request occurred.

No production code, version or dependency was changed. minSdk22 and
CONNECTIONS_ENABLED=false remain. No APK was built because only tests/docs
changed. The previously unresolved USBMUX peer RST is a separate protocol/
connection-lifetime question; no automatic reconnect was added.

Final focused command:

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests '*ExistingPairLookupReopenTest*' --tests '*LocalPairMetadataDiagnosticTest*' --tests '*AndroidDiagnosticPairStoreTest*' --tests '*AndroidControlledPairAccessTest*' --tests '*ControlledPairDiagnosticTest*' :shared:testDebugUnitTest --tests '*ControlledLockdownPairingTest*'
```

**42 tests passed**: common32/shared10, zero failures/errors/skips.
The new class contains6tests. Gradle BUILD SUCCESSFUL; editor diagnostics
clean. Initial test authoring exposed a reflection generic-type mismatch and
a Mockito null-matcher assertion problem; both were corrected in test code,
then the full focused selection passed. No production change was needed.
Document reference and whitespace checks also passed.

## Verdicts

The old report has a procedural explanation, not a proven false lookup.
Current live-device association has not been established by a P.1 rerun and
the transport reset remains unresolved; no USB validation test is authorized.

EXISTING PAIR LOOKUP STILL AMBIGUOUS — NO USB VALIDATE TEST

PAIRED RECORD SURVIVES INDEPENDENT STORE REOPEN
