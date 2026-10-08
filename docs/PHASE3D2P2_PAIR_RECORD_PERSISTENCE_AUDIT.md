# Phase 3D.2P.2 - Pair-record persistence / NOT_FOUND audit

## Procedure clarification - supersedes the original next-step recommendation

The user confirmed that DiPlay was **uninstalled** before P.1 was installed.
Uninstall can remove app-private preferences and UID-bound AndroidKeyStore
material. The reported empty inventory is therefore explained by installation
procedure and is **not evidence of a persistence bug or failed in-place update**.

Preserve the current installation. Recreate the record using the existing
manually confirmed controlled Pair diagnostic, then use the separate P.1
existing-record diagnostic with no Pair fallback. See the
[corrected procedure](PHASE3D2P1_EXISTING_PAIR_VALIDATE_TEST.md#corrected-procedure-after-confirmed-uninstall).
The existing Pair action attempts ValidatePair once after saving Pair SUCCESS;
it then cleans up and stops without sessions, TLS or services. No protocol
change is requested.

Do not implement the local metadata design below unless an accepted record
disappears after a confirmed same-package/same-signer in-place APK update.
The remaining audit preserves the evidence and recommendation available
before this user clarification; its original metadata recommendation and
final historical verdict are superseded by this section.

Subsequent user request: after a new Pair-success/validation-RST report, a
local-only metadata action was explicitly requested to establish current
accepted state. See [the later reset audit and implementation](PHASE3D2P2_PAIR_RESET_PERSISTENCE_AUDIT.md).
The old uninstall-related NOT_FOUND remains explained; it is not evidence
that the latest accepted record disappeared.

## Scope and conclusion

Static source/artifact inspection only. No USB/device operations, Lockdown
requests, pairing/trust changes, key generation, record reads from a real head
unit, repairs, migrations or new APK build were performed. This phase adds
only this document; the local metadata diagnostic below is a design, not code.

Authoritative hardware evidence:

- O.1: one Pair succeeded, `Pair success record stored=verified` was reported,
  then ValidatePair failed with DEVICE_UNAVAILABLE; cleanup succeeded.
- P.1: configuration transition/selection diagnostics remained healthy, but
  existing-record validation stopped at PAIR_RECORD_PREFLIGHT with
  PAIR_RECORD_NOT_FOUND. No device was selected; no transport was opened.

**The current NOT_FOUND is not a premature per-device identifier lookup.**
The preflight scans the local inventory without needing a USB device or UDID.
With the production Android store, this exact outcome means its loaded
preferences map contained no recognized `device_` entries. It does not establish
what remains physically on E01 storage, in another app/user namespace, in a
preferences backup file, or in AndroidKeyStore. No supplied local metadata
resolves why the earlier committed entry is absent from that map.

This is insufficient to claim actual record loss, an association bug, an APK
update erasing data, or failure to durably persist. A local-only inspection is
required before another validation or pairing test.

## 1. Production wiring and previous successful store

Sources:

- [manual action/store construction](../common/src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt)
- [controlled Pair state machine](../shared/src/main/java/com/shilapi/xcertplay/transport/ControlledLockdownPairing.kt)
- [Android protected store and codec](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt)
- [O/O.1 design and build evidence](PHASE3D2O_CONTROLLED_PAIR_VALIDATE_TEST.md)
- [prior persistence/cleanup audit](PHASE3D2P_VALIDATEPAIR_REUSE_AUDIT.md)

The activity uses `controlledPairStore ?: AndroidDiagnosticPairStore(app)`.
The nullable field defaults to null and has no production assignment; test
injection is not the real-car default. The diagnostic does not use the normal
Lockdown client's legacy store or an in-memory store.

Exact sequence in `ControlledLockdownPairing.run`:

1. GetValue(UniqueDeviceID), validate identifier shape, then `store.load(deviceId)`.
2. If absent in the combined Pair action, generate its identity once and save a
   PREPARED candidate under that actual device identifier. Save prepared
   certificate/key material to the same entry before Pair.
3. After an accepted Pair response, create a candidate with unchanged device
   association, identity and material, state **PAIRED**, and optional returned
   escrow. Set stage SAVE_PAIR_SUCCESS and call `store.save(candidate)`.
4. Only after save returns, wipe the temporary escrow buffer and append
   `Pair success record stored=verified`.
5. Attempt ValidatePair. Only a successful validation proceeds to
   SAVE_VALIDATION and saves **VALIDATED**. The supplied failed validation
   cannot have executed that later save.

There is no provisional USB-name/serial association, temporary record slot,
rename, migration, or delayed device-ID resolution in this flow. PREPARED
describes approval state, not a provisional identifier.

### Persistence location and logical key

App-private SharedPreferences `diplay_controlled_pair`, MODE_PRIVATE, obtained
from the application context. On API22 its conventional location is
`<applicationInfo.dataDir>/shared_prefs/diplay_controlled_pair.xml`; for the
expected debug package/user0, conventionally
`/data/data/com.shihab.diplay.legacytest/shared_prefs/diplay_controlled_pair.xml`.
This path is conceptual, not an observed E01 filesystem result.

Each preference name is `device_` plus the lowercase hexadecimal SHA-256 of
the validated UniqueDeviceID's UTF-8 bytes. No actual name/hash/identifier is
printed here. The same identifier is also inside the encrypted candidate.
The separate `system_buid` preference is identity metadata, not a record index.

The complete candidate is binary codec v1: association, identity, state,
material and optional escrow together. AES-256-GCM encrypts it with the entry
name as AAD; a random per-write AES key is RSA-wrapped with AndroidKeyStore
alias `diplay-controlled-pair-storage-v1`. The persistent RSA wrapping key is
not the host pairing private key; the latter resides inside the encrypted
record with its associated certificates. Neither is exported.

### Exact meaning of stored=verified

`AndroidDiagnosticPairStore.save`:

1. Loads the existing entry and refuses identity/material replacement or state
   downgrade; unchanged serialized data can return without rewriting.
2. Serializes and encrypts the candidate.
3. Replaces that one entry through `putString(...).commit()`.
4. Requires commit=true.
5. Reads the string back through **the same SharedPreferences object**.
6. Decrypts it using the storage key and requires byte equality with the
   serialized candidate. Clears temporary plaintext buffers.

For the PREPARED-to-PAIRED transition, state bytes differ, so the unchanged-save
shortcut does not apply. This is a synchronous disk-backed Android preferences
commit, not just an in-memory assignment or asynchronous `apply()`. Android's
commit contract reports successful writing to persistent storage.

However, the readback is not a new process, an independently reopened XML
file, or a head-unit restart/power-loss test. Constructing a second store in
the same process also can reuse Android's cached preferences object. The
message establishes successful commit plus in-process protected readback at
that time; it does not independently prove E01 filesystem/KeyStore survival
later. It also does not establish VALIDATED state or a usable session.

## 2. Exact current NOT_FOUND condition

Sources:

- [preflight and cleanup](../common/src/main/java/com/shilapi/xcertplay/ControlledPairDiagnostic.kt)
- [acceptedRecords inventory](../common/src/main/java/com/shilapi/xcertplay/AndroidDiagnosticPairStore.kt)
- [P.1 documented association timing](PHASE3D2P1_EXISTING_PAIR_VALIDATE_TEST.md)

`ControlledPairDiagnostic.run`, existingOnly branch (around lines 92-96):

```kotlin
stage = "PAIR_RECORD_PREFLIGHT"
val records = store.acceptedRecords()
if (records.isEmpty()) throw ControlledPairFailure(stage, "PAIR_RECORD_NOT_FOUND")
```

The production `acceptedRecords()` does not call `load(unknownDeviceId)`.
It rejects the legacy store marker, then enumerates
`prefs.all.keys.filter { it.startsWith("device_") }`.
For every recognized entry it decrypts/decodes, requires the hash of the
embedded association to equal the preference name, skips PREPARED, verifies
accepted material and identity, and collects PAIRED/VALIDATED candidates.

| Local condition | Current preflight outcome |
|---|---|
| No recognized `device_` keys | Empty list -> PAIR_RECORD_NOT_FOUND |
| Only readable PREPARED entries | PAIR_RECORD_PREPARED, not NOT_FOUND |
| At least one valid PAIRED/VALIDATED entry, no other scan failure | Preflight passes; phone association checked later |
| Recognized entry malformed, wrong type, missing key, undecryptable, bad association/material | Exception -> PAIR_RECORD_INVALID, not empty-list success |
| Legacy marker present | Exception -> PAIR_RECORD_INVALID; no import/clear |
| A different phone has an eligible accepted record | Inventory passes; exact lookup later may fail at LOAD_CANDIDATE |

Thus the supplied stage/reason, under the production wiring, rules out a
normal wrong-UDID hash lookup, only-PREPARED inventory, and a recognized
encrypted entry failing decryption as this specific code path. Merely losing
the wrapping key while retaining `device_` entries would not produce this
NOT_FOUND. Only identity metadata or unrecognized entries can still coexist
with an empty recognized inventory.

At this point no USB device/UniqueDeviceID is needed. `baseline` is still null.
The failure reporter may passively query Android's inventory, but reports
DEVICE_PRESENT=UNKNOWN because there is no selected device. This does not
mean the phone was absent and is not evidence of failed device association.
Healthy E/G configuration results cannot supply missing local preferences.

## 3. Association timing and safe lookup boundary

USB descriptors do not expose the Lockdown UniqueDeviceID used by this store.
It becomes known after USBMUX, TCP62078, QueryType and
GetValue(UniqueDeviceID). A strict pre-USB assertion that the connected phone
matches a record is not possible with this implementation.

**P.1 already separates these two checks correctly:**

- Before USB: at least one eligible protected accepted record exists locally.
- After discovery: `load(deviceId)` uses the actual phone identifier and checks
  association, identity, material and PAIRED/VALIDATED state.

Multiple accepted records are not inherently ambiguous: the later exact
device-ID lookup is deterministic. A missing match never permits Pair.
Restricting to exactly one local record is an optional future safety gate,
not a necessary fix to this NOT_FOUND.

If local inspection later proves that physical data exists under an invalid
or missing association, report that separately. Do not relabel it "no physical
record", guess a USB identifier, ignore authenticated association, or migrate
it automatically. Association/name mismatch is currently INVALID rather
than NOT_FOUND. No lookup redesign is justified by the present evidence.

A later separately approved validation design, only after store health is
established, can retain:

```text
local eligible accepted-record preflight
-> scoped USB/config5 -> version/setup07 -> USBMUX TCP62078 -> QueryType
-> obtain device identifier -> exact associated record load and verification
-> ValidatePair at most once -> cleanup -> STOP
```

No automatic Pair fallback, record selection by proximity, identity generation,
reconnection, session, TLS or service. This is not implemented or authorized
for a hardware test by this audit.

## 4. Failure cleanup and record invariants

Sources: the state machine above, the diagnostic finally block, and
[Session transport cleanup](../common/src/main/java/com/shilapi/xcertplay/AndroidReadOnlyLockdownAccess.kt).

- State-machine catch blocks rethrow/wrap failures only. They do not call save,
  remove, clear or key deletion. No failed-validation rollback exists.
- After the PAIRED save, validation failure bypasses SAVE_VALIDATION.
- Diagnostic finally releases a claimed USB interface and closes the
  connection; failure reporting and final-state checks do not touch storage.
- Session stopTransport verifies/closes channel, mux and pipe; release/close
  handle only transport resources. It has no store reference.
- Activity observer cleanup unregisters observation and updates UI/report.
  It does not modify the protected store.
- Store save has no delete operation and rejects PAIRED-to-PREPARED downgrade,
  identity replacement and existing material replacement.
- Production references to the protected preference name and wrapping alias
  are confined to the Android store; no `deleteEntry`,
  `deleteSharedPreferences` or `clearApplicationUserData` calls were found in
  the inspected production modules.

Therefore no path in this audited failure/cleanup flow erases, downgrades,
renames, clears association, removes wrapping keys or rolls back the accepted
record. A complete successful record write is one encrypted entry commit;
it is not an atomic transaction with the iPhone or across preferences/KeyStore.
External data changes, storage failure or vendor behavior remain unobserved.

## 5. APK update, package and signing evidence

Source [mobile build configuration](../mobile/build.gradle.kts),
[manifest](../mobile/src/main/AndroidManifest.xml), and recorded O.1/P.1 builds:

- Both diagnostic builds use applicationId `com.shihab.diplay` with debug
  suffix `.legacytest`: expected package `com.shihab.diplay.legacytest`.
  Only versionNameSuffix changed between O.1 and P.1; code31/minSdk22 remain.
  The current P.1 APK's aapt metadata independently confirms this package.
- The source namespace `com.shilapi.xcertplay` is not the installed package
  storage identity. Different version labels do not create new data paths.
- Signing configuration did not change; both use the Gradle debug signing
  configuration, with v1/v2 enabled. The current P.1 APK public signer SHA-256
  fingerprint is
  `659c3de738cfbf38c71ad7c03b350eb98593600c5647bb52fd077f06c1730b5b`.
- The original O.1 APK has been overwritten at the build output path. Its
  recorded SHA-256 is an APK hash, **not** a signing certificate fingerprint.
  No archived O.1 signer fingerprint is available here. Therefore byte-level
  equality of the two signing identities cannot be independently certified
  from these artifacts. Unchanged configuration is not proof the local debug
  keystore was never replaced.
- A normal Android same-package, same-signer in-place update retains app data
  and UID-bound KeyStore access. A signing mismatch ordinarily rejects the
  update; it does not silently reset data. An uninstall/reinstall workaround,
  clear-data operation, different package/build variant or Android user would
  change this analysis, but none is established by the supplied results.
- There is no version-triggered protected-store migration or cleanup. Codec,
  preference name, association derivation and wrapping alias remain v1.
  `allowBackup=false` supplies no assurance of restoration after uninstall.

Do not assume update erased data. A local metadata action should report the
package/variant-match boolean and storage-presence facts without dumping data.
Comparing a retained original O.1 APK's public signer digest with P.1 is a
separate safe offline check if that artifact becomes available.

## 6. Required next step: safe local metadata diagnostic design

Design only, for a separately approved local-only phase. Manual Settings action
"Inspect local DiPlay pair-store metadata"; no USB inventory, receiver,
transport adapter or USB permission access. Its only injectable dependencies
are local preferences/file-presence facts and read-only KeyStore access.
Do not reuse the USB diagnostic engine or `systemBuid()`/save/encrypt/keyStore(true),
as those can create state. Do not use acceptedRecords alone: it hides
PREPARED entries and aborts on the first invalid entry.

Permitted output consists of counts, booleans and fixed internal status enums:

- Expected package/variant match; preference XML and backup-file existence
  booleans using applicationInfo.dataDir, never file contents or path exports.
- Total preference entries, recognized record-entry count, unknown-entry count,
  identity-metadata-present boolean. No names, device hashes or identifier values.
- Read-only AndroidKeyStore load success and wrapping-alias-present boolean.
  Check alias even when inventory is empty; never generate or delete it.
- Per-entry scan-local row number (not a durable identifier), value-type-valid,
  envelope-readable, decryptable, codec-valid, association-present,
  association-key-matches, material-present and material-valid booleans;
  state PREPARED/PAIRED/VALIDATED or UNKNOWN.
- Counts grouped by fixed errors: STORE_READ_FAILED, WRONG_VALUE_TYPE,
  ENVELOPE_INVALID, STORAGE_KEY_MISSING, DECRYPT_FAILED, CODEC_INVALID,
  ASSOCIATION_MISSING_OR_INVALID, ASSOCIATION_MISMATCH, MATERIAL_INVALID.
  Preserve UNKNOWN rather than guessing a state when decode fails.
- Orphan indicators: wrapping-alias-with-zero-recognized-records and
  identity-metadata-with-zero-recognized-records. These are evidence, not
  authority to clean anything. No separate association index exists to orphan;
  record/key mismatch and unrecognized entries are reported explicitly.

Per-entry failures must produce explicit fixed status and continue the local
inventory scan; no silent omission or false "no records" result. A store-wide
read failure must make counts UNKNOWN, not zero. Stage exceptions must not
export arbitrary messages, object toString values, plist or credential data.
Decoded association may be compared internally, never reported. Credential
buffers are temporary/local and cleared where possible; no export of bytes,
identities, device identifiers, serials, certificates, keys or escrow.

This distinguishes:

| Metadata evidence | Interpretation / safe next step |
|---|---|
| No recognized entries, wrapping alias exists | Entry absence with retained key; loss cause still needs installation/storage history |
| No entries, no identity metadata/key, expected package mismatch | Namespace mismatch evidence; inspect correct app, do not copy/import |
| Entries present, missing/unusable wrapping key | Protected records still physically may exist; not NOT_FOUND; no key regeneration |
| Decryptable PAIRED/VALIDATED with valid association/material | Accepted local state present; compare actual preflight wiring/context before proposing lookup fix |
| Decryptable record with bad/missing association | Physical record != usable associated record; no automatic migration |
| XML/backup present but loaded inventory empty | Evidence for storage/cache/load investigation; never print or repair XML automatically |
| Only PREPARED records | No eligible accepted local record; do not Pair to "repair" |

No outcome authorizes Pair, ValidatePair, StartSession or any phone request.
Do not clear data, uninstall, delete aliases or overwrite/migrate records.

## 7. Tests and verification limits

Existing source tests inspected, not rerun or extended in this documentation-only
phase:

- [Android store tests](../common/src/test/java/com/shilapi/xcertplay/AndroidDiagnosticPairStoreTest.kt):
  encrypted reopen/load, association mismatch lookup, corruption/wrong-key
  failure without regeneration, replacement/downgrade guards, and valid PAIRED
  inventory discoverability after constructing another store.
- [controlled state-machine tests](../shared/src/test/java/com/shilapi/xcertplay/transport/ControlledLockdownPairingTest.kt):
  cancellation after Pair retains PAIRED; accepted states skip Pair; validation
  transport failure retains PAIRED and performs no save/fallback.

These use Robolectric/software keys or injected stores and are not independent
E01 process/head-unit restart or AndroidKeyStore durability evidence.
The later metadata-only implementation should test PAIRED and VALIDATED
discoverability, PREPARED counts, missing/bad association distinct from empty
inventory, mixed corrupt/valid entries, alias-only/identity-only orphans,
missing/unreadable keys, no writes/generation and zero USB/phone calls. Add a
controlled fresh-process/restart persistence check only for local storage;
a second wrapper in the same process is not that check.

No production code, tests or version were changed; no APK was built in P.2.
Current source/APK minSdk22 and
[CONNECTIONS_ENABLED=false](../shared/src/main/java/com/shilapi/xcertplay/orchestration/LegacyLaunchBuild.kt)
were inspected and remain unchanged. Normal CarPlay startup stays disabled.
Only documentation references/whitespace are validated for this audit.

## Recommended next phase

Implement and test the manual **local-only metadata inspection** described
above, then obtain its safe E01 report before deciding on a storage/lookup fix.
Retain the current no-Pair guard. Do not retry the combined Pair action or
implement another hardware ValidatePair test on this evidence.

PAIR RECORD STATE UNKNOWN — LOCAL METADATA DIAGNOSTIC REQUIRED
