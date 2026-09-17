# POC delivery: Language PR 51 qualification

## Scope

This note records delivery gates after the focused pre-PR qualification. Earlier
POC reports retain their original source tuples and results; they are not evidence
that the full current CI inventory has passed.

## First broad CI result

Build run `35275491743`, source `e1a4e2dc`, completed the root suite with
2,798 tests: 2,795 passed and three failed. The build stopped at that task; this
does not qualify later module or release tasks. Failures and corrections:

1. Module ownership omitted the eleven new storage/reuse production files.
   Regenerate the physical ownership manifest and API relocation ledger with the
   existing generator, classify the seven new public codec classes and their
   nested API types as supported additions, and update the exact source count
   from 817 to 828. Fourteen public types are added; no previous classifications
   change and the dependency-ownership report is unchanged. Exact inventory and
   classification assertions remain enabled.
2. The terminal-result document marked an API-signature inventory as a runnable
   Java block, without a compiled example binding. Present those signatures as an
   explicit API inventory instead. Actual runnable Java example binding and
   compilation checks remain unchanged.
3. A null-argument diagnostic in `FrozenNodeStorageCodec` used the bare string
   `value`, reserved by the wire-vocabulary source convention. Rename the
   diagnostic to `frozen node`; no wire key, encoded byte, processing outcome or
   validation rule changes.

These are release-convention corrections, not a change to storage semantics or a
relaxation of any test. Focused requalification and subsequent broad CI outcomes
are recorded in the PR checks and follow-up entries below.

Focused local requalification on JDK 17 passed 18 root architecture/style/docs
controls and seven exact storage codec controls: **25/25**, zero failures,
errors or skips, in 12 seconds. The API union was regenerated from all seven
compiled published modules. Subsequent CI must still qualify the complete branch.

## Post-merge release inventory correction

Both exact-head builds (`35276838249` and `35276842696`, source `85a32d20`)
passed, including their five transition groups. PR 51 was merged as `4a1742d2`.
The post-merge build `35278524435` also passed. RC run `35278524429` then passed
its build and transition groups but stopped at the release-only sentinel audit;
no version/tag was pushed and no artifact was published by that run.

The audit still described the earlier source tree. Regeneration with the
unchanged generator preserves all 256 decision-relevant classifications, while
refreshing source positions and adding a net 44 context-only search matches
(2,113 → 2,157 total). The new codec property-count writes retain distinct
encodings for absent (`-1`) and present empty (`0`) properties; this inventory
refresh does not change their implementation or claim new semantic evidence.

An independent check of the other release preflights also found eleven new
storage/reuse sources absent from the implementation inventory. Regenerate that
closed inventory with its existing generator (742 → 753 paths); preserve exact
source discovery, sorting, uniqueness and role-projection checks. Update the
two stale exact test counts to 753 runtime files and 253 Language model/core
files (the prior runtime assertion still said 741 despite a 742-path inventory).
Historical fixture-transition counts remain unchanged.

This correction changes generated inventories and their exact count assertions,
not runtime Java, protocol rules, fixtures, API descriptors or release workflows.
The regenerated release preflight, identity-impact inventory and final API
baseline checks pass locally. All 23 focused generator, stale-report diagnostic
and implementation-inventory tests also pass. Full release qualification remains
required; a local `rcVerify --continue` diagnostic is not a clean-source release
certificate and must not replace the automatic RC pipeline.
