# Detached paths may attach another managed lineage

The user/CTO confirmed on 2026-09-12 that removing B and later attaching C at
the same managed path is supported business behavior. The prior blanket
inactive-retarget prohibition in Contracts and the rejection-only runtime
patches implemented the wrong rule.

## Generation and identity

Removal of active B generation 1 still commits its inactive reservation at
generation 2. A later exact selection of C uses that already reserved generation
2, with fresh occurrence/binding identities because the target DocumentId is
an identity operand. There is no extra generation just for replacing the
inactive reservation. The original pre-workaround MyOS oracle used B1,
detach/re-add B2, detach to reserved B3, then C3; it did not require B2 to C3.

The immutable invocation input remains B's row. Exact typed demand resolution
selects C and its current or retained historical epoch. Only consumption of
the actual post-effect demand derives the replacement row in tentative state;
read expansion cannot rewrite the input reservation. Binding policy and path
are preserved. Byte equality alone still does not select managed lineage.

At C's current epoch, the replacement activates in this invocation and the
graph receipt is ADD: there was no active before edge to REBIND. This includes
C at epoch zero; it must not create an impossible 0-to-0 catch-up obligation.
An older selection, including authenticated authored epoch -1, remains inactive
with its exact selected cursor. Existing managed-history reconciliation catches
up only C, retains its current authoritative head and activates with ADD when
the captured boundary is reached. B's history, checkpoints and pending work
are never transferred to C. Active different-lineage REBIND still uses the
next generation and its complete active before/after receipt.

## Preserved guards

The new private selection predicate requires an unchanged inactive, pending-null
input row, exact source/path, and initialized reserved and selected lineages.
Future selected epochs and same-epoch wrong BlueIds fail. Earlier exact states
use the existing authenticated host-history contract. Missing endpoints,
forged/wrong-cause demands, unused resolutions, changed binding policy or
generation, and fabricated success receipts remain invalid. The original
same-invocation retirement fence is unchanged; a newly retired row cannot be
laundered through resource expansion or this input-row predicate.

The numbered-successor imported-event source-epoch correction remains intact.
No witness, frozen-receiver, source-head, CAS, gas, rollback or replay rule is
relaxed. No new public syntax or identity constructor operand is introduced.

## Verification status

Implementation and focused regression changes are prepared in the isolated
Language branch based on qualified source 34e9. The final focused gate passed
94/94 tests with no failures, errors or skips on 2026-09-12, including the
updated exact specification identity. The evidence archive is
`language-focused-03.tar.gz`, SHA-256
`5edb19b42f5e970259be11ee89b15d053cdc6f2c99bc8a23eb1b4431c80d9b7e`.
The primary regression covers current and historical C in direct/reference
forms, actual committed detach/re-attach, equal-byte B/C lineage selection,
exact retry negatives, input preservation and C-only catch-up. The existing
same-lineage re-add, active retarget, receipt, read-expansion and retirement
fence suites were included. The actual same-invocation remove/foreign-add
control returns complete `RUNTIME_FATAL` with the existing ineligible historical
replacement diagnostic and literal input rollback, no graph/checkpoint/public
event writes or transition receipts, and no commit companion. It is distinct
from the newly legal later-invocation selection.

Normative prose mirrors and identity-constructor/schema descriptions are
updated, including the rooted specification pin and companion specification
inventory. Historical RC24 text and import provenance are preserved. Release
identities and active pins were refreshed from the supported full staged
regeneration of clean source `13546630997dd8f839a2e562f1c210fd63ce4484`.
The exact 383-file inventories retain the same paths; 86 files change: 80
executable fixtures, one trace, two normative description files and three
manifests. Independent comparison found 30,140 specification-derived identity
leaves, 2,507 consistent identity substitutions, and no executable non-identity
input, body, status, gas, checkpoint or ordering change. All 741 implementation
hashes match that source. The four prose leaves match the reviewed semantic
correction; no constructor operand or schema validation constraint changes.

The old manifest-only review and mutation controls remain intact. A separate
exact complete-inventory pair admits only this generated amendment, with the
two exact prose files classified explicitly as normative semantic changes.
The separate two-fixture historical-representation package changes only its
specification/package binding; both executable JSON recipes stay unchanged.
The aggregate release manifest and named Java pins follow their derived
identities. Generator/checker acceptance does not claim runtime conformance.

The strict generated-pair classifier passes with 86 changed files and zero
unexpected changes; all ten old/new exact-pair and mutation controls pass.
The documentation-generation retry passes, including 480/480 release
conformance cases (185 Language and 295 Contracts). Its archived reports are
`language-doc-generation-02-reports.tar.gz`, SHA-256
`53c4fda51f637d2d009e22a483ad2210f518d2dd703ba1234a269f6cad92c2bf`.
The eight generated reference files match the generated bytes; only eight
existing identity lines change. The sentinel inventory remains current, and
the identity-impact inventory refresh changes only sixteen accepted source
or derived identity bindings, retaining all classifications and zero active
old-identity references, unresolved artifacts or mirror mismatches.

The successful documentation retry explicitly pins `-PbluePythonExecutable`
to the isolated `legal-detached-retarget-evidence.fKnxrU/python-env/bin/python3.13`.
The default Python 3.9 failed parsing an existing archived UTC `Z` timestamp;
neither that historical record nor its validator was changed. Final
qualification must retain the same explicit Python 3.13 selection.

The first exclusion-free clean build on sealed source
`5a103afb369b710e3bf2b1158362220607604a07` stopped after 20m17s at one stale
C-CLO-34 file-digest assertion in `ClosureConformanceHarnessTest`. The root
project passed 2,799/2,799 tests and conformance passed 181/182: 2,981 executed
tests in total, one failure, zero errors or skips. Remaining modules and final
quality/RC verification did not run; this is not a full-build pass. The preserved
archive is `final-language-5a103.56avBS/final-reports.tar.gz` under
`legal-detached-retarget-evidence.fKnxrU`, SHA-256
`14847c3a8bec97c59968d89e1e22d71aa37d444144da21c15e6e7f74040d527c`.

The corrected test pins the already reviewed fixture digest
`84e023e0eac568152e0fd885e7d50f3f89d72ea3c6df45208c33923a72f2e296`
instead of its pre-regeneration digest `5bdf1321...`; its 119,322-byte assertion
and all execution assertions remain unchanged. The current fixture matches the
approved stage exactly. Its 200 changed leaves are specification-derived
identities only: success, gas 1,423, resulting documents, graph changes,
checkpoints and ordering remain unchanged. All 383 package files match the
reviewed complete inventory. A scan of all 86 changed file hashes and 2,507
old derived identity operands found no other stale active test/code literal;
the self-contained historical admission capture retains its own environment.
No runtime, specification, generated fixture, manifest or classifier record
changes accompany this test-pin correction.

A new exclusion-free clean build and final quality/RC gate remain required.
The prior 34e9 clean/quality pass does not qualify this candidate.
