# Phase 3D.2C1 - QDrive Valeria branch discriminator

**Superseded error handling by Phase 3D.2C2:** the real E01 scan read config
1/PTP and `"PTP"` but stopped at config 2/interface 0 with iInterface=0.
The [C2 native re-audit and completion fix](#phase-3d2c2---native-error-handling-and-complete-iteration)
below corrects that stop and the earlier buffer-initialization interpretation.

## Exact static condition (correction to Phase 3D.2C)

Audited artifact: [libusbserver.so](../vendor-apks/QDrive_Global/lib/arm/libusbserver.so),
SHA-256 `87748337AB3B0BB70AFB037E412454C74B1BB419CA01E270B666EB225C8AEA54`.
The binary was disassembled locally, never executed or loaded.

The previous audit correctly found a string gate but did not establish its
comparison operation. It must **not** be interpreted as "exact equality only."

At native helper `0x95574`:

1. Device descriptor offset 17 supplies `bNumConfigurations`.
2. Configuration array indices are visited from 0 to count minus one.
   `0x955A4` calls `libusb_get_config_descriptor`, not an active-only getter.
3. Each `libusb_interface` array element is visited, in parsed descriptor order.
   `0x955B6` loads its `altsetting` pointer, and `0x955C0` reads offset 8 of
   that **first alternate's interface descriptor**, its `iInterface`.
   There is no inner loop over other alternates, no class filter, and no
   special preference for USBMUX ID 1. Configuration name/iConfiguration,
   product name and serial string are not the comparison inputs.
4. `0x955CA` calls `libusb_get_string_descriptor_ascii(handle, iInterface,
   buffer, 255)`. The compiled routine at `0x9F194` obtains descriptor zero,
   chooses its first LANGID, then fetches only that indexed string.
   Both standard GET_DESCRIPTOR transfers use type `0x80`, request 6,
   length 255 and timeout 1000 ms. Conversion maps non-ASCII UTF-16 code
   units to `?` and terminates the output with NUL.
5. `0x955D2` calls PLT `0x78B00`, whose ARM instructions resolve to GOT
   `0x2CE658`, relocation **strstr@LIBC**.
   The search arguments are the retrieved ASCII string and `"Valeria"`
   at `0x26DD01`. It is a **case-sensitive substring search**, not strcmp.
6. `0x955D6` branches on a **non-null strstr result** to `0x955F6`, which
   returns TRUE. Otherwise scanning continues. Exhaustion returns FALSE
   at `0x955F2`.

At device-add `0x95B68`, that helper result controls `cbz r0, 0x95B9A`
at `0x95B6C`:

- **TRUE / any checked string contains `Valeria` -> configuration-selection
  branch** starting at `0x95B6E`. It reads current configuration, compares
  it to the device configuration-count byte and selects that value if different.
  Four configurations imply target 4 in this collected implementation.
- **FALSE / all checked strings lack `Valeria` -> vendor-request branch**
  at `0x95B9A`: type `0x40`, request `0x52`, value 0, index 2,
  no data, length 0, timeout argument 0. This is not SET_CONFIGURATION(2).
- The diagnostic performs **neither branch**.

The ELF's PLT/relocation resolution is decisive. A function label inferred
from a nearby string or exported symbol would give the wrong comparison.
Any interpretation as strcmp/equality or an inverted non-equality predicate
must be superseded by this resolved call target.

## Read-only implementation boundary

[QDriveBranchDiagnostic](../common/src/main/java/com/shilapi/xcertplay/QDriveBranchDiagnostic.kt)
is manual, injectable and has no transport/authentication dependencies.
[AndroidQDriveDescriptorAccess](../common/src/main/java/com/shilapi/xcertplay/AndroidQDriveDescriptorAccess.kt)
requires exactly one selected Apple device with existing permission, rechecks
identity/permission before opening and never requests permission.

The report first lists all cached configuration and interface names, IDs,
alternates and classes via public API22 metadata. Cached names alone do not
establish which LANGID QDrive would use, nor expose iInterface indices.
The adapter therefore opens without claims, reuses the Phase 3D.2B
GET_CONFIGURATION reader, obtains `getRawDescriptors()`, validates the complete
configuration layout and uses first-alternate iInterface indices only.

Only standard GET_DESCRIPTOR **STRING** requests are permitted beyond the
current-configuration read: index zero for first LANGID, then the needed
iInterface index in that language. Other strings/configuration descriptors
are not fetched, serial/product/manufacturer strings are not requested,
and no vendor request is exposed. C2 reads each entry independently without
string/LANGID caching, matching the native per-entry reads. It stops on the
first successful match just as QDrive does.

The raw parser rejects malformed/truncated/duplicate configuration IDs,
incorrect interface counts and incomplete advertised configuration inventory.
It bounds the inventory to eight configurations and 64 checked interfaces.
All control requests use 1000 ms timeouts; requests are sequential, never
automatic, and connection close occurs in finally before the result is
published. There are no per-entry retries. Missing indices and native-rejected
string reads continue to subsequent entries as detailed below.

The previously documented 0xFF initialization was incorrect: the resolved
routine is `__aeabi_memclr8(buffer, 255)`, which zeroes 255 output bytes.
Missing indices, negative native read returns and string validation failures
that precede conversion leave this output empty and continue without a match.
Raw descriptor failure and unresolved native malformed-input behavior still
prevent a false-result verdict. Cleanup failure prevents a confirmed verdict.

Report distinction:

- Literal string `Valeria` equality is informational.
- `Valeria condition = MATCH` means case-sensitive **substring present** in a
  checked first-alternate interface string -> helper TRUE.
- `NO MATCH` means the complete scan has no match and no unresolved entry;
  missing indices/native-rejected reads contribute a known empty predicate
  input -> helper FALSE.
- `UNAVAILABLE` means insufficient read evidence; neither branch may be inferred.

The configuration-count/value equivalence is QDrive behavior, not a general
USB rule. C2's verdict supports the configuration-selection **branch**, not a
particular configuration target or a successful configuration change.

## Validation and car procedure

Focused tests cover exact/substrings, case sensitivity, mismatches, missing
names/indices, real four-configuration interface layout, first-alternate
selection, later matches, malformed raw/string descriptors, read failures,
permission/open failures, cleanup and compiled forbidden-capability exclusion.
Mocks allow only getters, GET_CONFIGURATION, standard string GET_DESCRIPTOR
and close; every other manager/connection interaction fails verification.
UI tests prove not-run/manual state and saved-report inclusion.

PC validation: 37 focused tests passed (12 discriminator tests and 25
descriptor/current-configuration/UI/export regressions), zero failures/errors.
`:mobile:assembleDebug` succeeded. APK manifest inspection confirmed
`sdkVersion=22`, package `com.shihab.diplay.legacytest` and version
`0.2.12-api22-phase3d2c1-interface-strings`. These are PC results, not evidence
of the real iPhone's interface strings.

Build:

```powershell
.\gradlew.bat :common:testDebugUnitTest --tests 'com.shilapi.xcertplay.QDriveBranchDiagnosticTest' --tests 'com.shilapi.xcertplay.PassiveUsbInventoryTest' --tests 'com.shilapi.xcertplay.PassiveUsbConfigurationMappingTest' --tests 'com.shilapi.xcertplay.ActiveUsbConfigurationDiagnosticTest' --tests 'com.shilapi.xcertplay.Phase3BDeviceSettingsTest' --tests 'com.shilapi.xcertplay.DiagnosticExportUiTest'
.\gradlew.bat :mobile:assembleDebug
```

minSdk remains 22; `CONNECTIONS_ENABLED=false`. APK:
`mobile\build\outputs\apk\debug\mobile-debug.apk`, version suffix
`-api22-phase3d2c2-complete-interface-strings` for the corrected C2 APK.

1. Remove Carlinkit.
2. Start Geely normally.
3. Unlock iPhone.
4. Connect iPhone directly.
5. Open DiPlay.
6. Settings/Diagnostics: run only **Inspect iPhone interface strings**.
7. Wait for cleanup and **Save diagnostic report**.
8. **STOP.**

Do not approve Trust or run another USB test. No configuration selection,
vendor-mode request, claim, alternate change, bulk transport, USBMUX,
Lockdown, pairing/session, CarKit, iAP2, MFi, Ethernet/NCM, Bluetooth,
AirPlay or CarPlay is performed. Phase 3D.2D remains unimplemented.
The real E01 branch verdict remains unavailable until this report is collected.

## Phase 3D.2C2 - native error handling and complete iteration

### Real-car regression

The directly connected `05AC:12A8` opened successfully, GET_CONFIGURATION
returned 1/PTP, raw descriptors were retrieved, and the first examined string
was `"PTP"`. Config 2/interface 0 had iInterface=0. C1 incorrectly threw
`IllegalStateException: Missing iInterface string`, never reaching configs 3/4.
This did not change the phone's configuration and does not establish either
QDrive branch.

### Reverified instruction evidence

Same libusbserver hash as above, read-only static disassembly only:

- `0x955B4`: r1=255; `0x955BA`: r0=output buffer;
  `0x955BC`: call PLT `0x78818`. ARM PLT arithmetic resolves to GOT
  `0x2CE560`, relocation **__aeabi_memclr8**. Its signature is (buffer, size),
  so **255 bytes are cleared**, not filled with 0xFF. This happens each entry.
- `0x955C0` loads iInterface; `0x955CA` calls the ASCII reader.
  Its return value is not tested before the substring search at `0x955D2`.
- In the ASCII reader, `0x9F1AA/0x9F1B2` check string index zero and branch
  to `0x9F220`: return -2 **without requesting anything or writing output**.
- Negative LANGID read returns at `0x9F1D6/0x9F1D8`, LANGID response shorter
  than four bytes at `0x9F1DA/0x9F1DC`, and negative interface-string returns
  at `0x9F206/0x9F208` exit before output conversion.
- Descriptor zero's first LANGID is loaded from bytes 2/3 without checking
  its type/bLength header. C2 follows that behavior when at least four bytes
  actually arrived, rather than applying a stronger proxy condition.
- Interface string type !=3 (`0x9F20A`-`0x9F210`) or returned count smaller
  than bLength (`0x9F212`-`0x9F218`) returns -1 before output conversion.
- For a valid empty descriptor bLength=2, `0x9F240/0x9F242` reaches the
  zero-length conversion return and NUL termination at `0x9F27E`.
- `0x955D2` calls strstr via PLT `0x78B00` / GOT `0x2CE658`. Non-null at
  `0x955D6` jumps to helper TRUE at `0x955F6`. Null advances the interface
  loop, then configuration loop. Exhaustion returns FALSE at `0x955F2`.
- Caller `0x95B6C` takes configuration selection for TRUE and the separate
  vendor request for FALSE. Neither is executed by the diagnostic.

| Input/result | Proven native predicate/iteration | C2 handling |
|---|---|---|
| iInterface=0 | Cleared output remains empty; strstr is null; continue | NO_STRING_INDEX, match=false, CONTINUE, no string request |
| Negative descriptor transfer | Reader returns before writing output; continue | STRING_READ_FAILED, match=false, CONTINUE |
| LANGID transfer count 0..3 | Rejected before output; continue | MALFORMED, match=false, CONTINUE |
| String type !=3 / count < bLength with available header | Rejected before output; continue | MALFORMED, match=false, CONTINUE |
| Valid empty string | Empty NUL-terminated output; continue | STRING_READ_PASS, ASCII="", match=false, CONTINUE |
| Valid nonmatching string | strstr null; continue | STRING_READ_PASS, match=false, CONTINUE |
| Valid string containing case-sensitive Valeria | strstr non-null; immediate TRUE | STRING_READ_PASS, match=true, TERMINATE TRUE |
| bLength 0/1 with available type-3 header | Converter writes empty output; continue | MALFORMED, match=false, CONTINUE |
| String transfer count 0/1 or odd bLength accepted past native checks | Native can consume uninitialized/out-of-logical-range bytes; deterministic predicate not established | MALFORMED, match unavailable, continue collecting; no FALSE verdict |
| Android transfer exception | No equivalent native return code established | STRING_READ_FAILED with exception detail, continue collecting; no FALSE verdict |
| Malformed/missing raw configuration/interface structure | Native ignores config-fetch return and dereferences its descriptor pointer; safe equivalent traversal not established | Structural failure, UNAVAILABLE, close; do not invent entries |

A later **successfully retrieved** matching string establishes the positive
predicate even if an earlier entry was unresolved; a negative verdict requires
complete traversal with every entry either successfully read or provably
empty under the native pre-conversion failure paths. No uninitialized native
memory is emulated. No cached Android name substitutes for a native string.

Each checked entry reports configuration index/ID, interface ID, first alt,
class/subclass/protocol, iInterface, status, ASCII when available, substring
result and continue/terminate decision. Individual errors are explicit, never
silently defaulted to success. Device/permission/open/current-config/raw
inventory failures and cleanup failures still yield UNAVAILABLE.

Regression fixtures include PTP -> missing config-2 index -> config-3 USBMUX
strings -> config-4 USBMUX/Ethernet strings, with alternate 1/2 excluded;
a later Valeria match terminates immediately. Read failures and malformed
native-rejected entries are tested with both later matches and complete
no-match scans. Odd/header-short cases remain uncertain after complete scan.

C2 verdict strings:

- `VALERIA CONDITION MATCHES — CONFIGURATION-SELECTION BRANCH SUPPORTED`
- `VALERIA CONDITION DOES NOT MATCH — VENDOR-REQUEST BRANCH SUPPORTED`
- `VALERIA CONDITION COULD NOT BE DETERMINED`

C2 PC validation: **43 focused tests passed**, zero failures/errors
(18 discriminator tests plus 25 descriptor/current-configuration/UI/export
regressions). `:mobile:assembleDebug` succeeded. APK manifest confirms minSdk
22 and version `0.2.12-api22-phase3d2c2-complete-interface-strings`;
`CONNECTIONS_ENABLED=false`, vendor integration and normal transport-test gates
remain disabled. No real-hardware branch verdict is inferred from these tests.

Safety and real-car procedure above are unchanged. No live Phase 3D.2D
configuration test was implemented. No configuration/alternate setter, vendor request, claim,
bulk transfer, vendor JNI or transport/authentication start was added.

### Completed real-E01 C2 result and subsequent static audit

The user subsequently reported a complete scan of the directly connected
`0x05AC:0x12A8` iPhone, with permission granted and active configuration 1/PTP.
Retrieved strings included `PTP`, `Apple USB Multiplexor` and
`AppleUSBEthernet`; no checked first-alternate string contained case-sensitive
`Valeria`.

**VALERIA CONDITION DOES NOT MATCH — VENDOR-REQUEST BRANCH SUPPORTED**

This is real-hardware evidence, separate from PC regression tests. Do not
pursue the Valeria/configuration-selection branch on this baseline.
The [Phase 3D.2D static audit](PHASE3D2D_QDRIVE_VENDOR_REQUEST_AUDIT.md)
recovers the FALSE request and proposes, but does not implement or execute,
a minimal Phase 3D.2E experiment.
