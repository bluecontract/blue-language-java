# C03 deferral — baseline first

## Approved boundary

The user approved completing the baseline without the whole C03 contextual
Source-identity correction, then considering its upstream delivery separately.
The baseline remains pinned to merged Language
`450230c18a41df8f873fb761d516f1bc85d7b5bd`; no open-PR source or API is adopted.

The exact e101-to-450 reversal comprises:

- `CanonicalIdentityInputReconstructor`: remove the candidate-only custom-type
  identity policy.
- `MinimizedOverlayBuilder` and `MinimizedOverlayReconstructor`: remove the
  unpublished Source-aware overload and its candidate-only provenance policy;
  retain the published two-argument behavior exactly.
- `BlueLanguageRuntime`: return minimization to the pinned two-argument call.
- `blue-language-core/api/public-api.txt`: remove only that added descriptor
  and restore the pinned 1148-entry inventory.
- Remove the two candidate-only Core test owners,
  `NestedValueReferenceIdentityTest` and `SourceAwareMinimizedOverlayTest`, from
  this baseline. Their 5 and 12 invocations are deferred, not reported as passes.

The complete Core production and test trees and its API inventory now match
the pinned base. Model, Mapping, Java and IPFS production code also remains
identical to that base. This does not remove the five Contracts corrections:
detached-path retarget, imported successor source-epoch classification,
immutable witness contexts, fresh-terminal witness selection, and working
Source-contribution selection including its positional-replacement fix.

The retained selected-scope root test constructs exact post-capture content
and hashes it directly under Contracts' capture rule. Its source-versus-canonical
oracle correction was discovered during C03 work but does not assert the
deferred contextual identity policy. It remains an independent regression gate,
not a fresh PASS claim.

## Known acceptance exception and preservation

The original packaged MyOS inherited-typed-child case remains a known failure
with upstream Core: `Holder.terms: Terms`, with the same typed Coffee child
inline or by its verified exact reference, has equal direct parent identity
but unequal Source-derived identity. Deferral does not classify that mismatch
as intended behavior. The associated no-field-type control is distinct.

The root-owned MyOS qualification must record its exact approved exception;
it cannot claim the unchanged full zero-skip acceptance scope. No Language
regression expectation is changed to bless the mismatch, and no unrelated
native tests, history/gas/failure assertions or release guards are removed.

The previous candidate remains recoverable at
`e1015d152b54e1da2879a7186647061a5196cf9c`, including both removed test files.
The separate `language-contextual-identity` worktree and its unfinished WIP are
untouched. Existing e101 artifacts and all failed/passing diagnostic evidence
remain historical; none qualifies this changed baseline.

## Reviewed generation and remaining qualification

This source edit does not manually rewrite generated fixture expectations.
The maintained cyclic-only bootstrap has restored the two implementation roles
to their pinned-Core values, with matching Contracts API constants. The existing
independent lifecycle helper changes only its two reviewed role constants;
its original role pair and all frozen event/work/gas/rejection constructors and
assertions remain intact. The bootstrap receipt is external to this source.
The 742-path implementation inventory and 817-source ownership count remain
unchanged: no production file was added or deleted. Four Core implementation
digests and the derived Contracts runtime-role binding change against e101.
The Language specification
and source-preprocessing specification binding remain at pinned upstream;
the approved Contracts retarget amendment is retained.

Clean input `6f9af4728a4a109684ea315de2f41cd6bf61f8fe` passed the grouped native
gate: Core 173/173, Contracts 612/612 and nine selected root owners 90/90, with
no failures or skips. This includes minimization, exact-capture, architecture
and API checks, not the full native qualification. Gradle used Java 17; the
actual test executors used the maintained Java 8 runtime. The incorrectly
qualified SourceStyle selector matched no owner and is not claimed as coverage.

Maintained full package generation from that clean input completed in 1166.874
seconds with source unchanged. Independent complete-byte/leaf review and static
package validation passed before exact maintained publication of its 383-file
Contracts subtree. Relative to frozen donor e28, only ten existing Contracts
source hashes and the derived release identity change; all other 382 files,
including all 295 executable fixtures, are byte-identical. Against e101, 80
closure fixtures and one separate trace change only derived identity fields;
numeric gas, outcomes, payload/state/history and order remain unchanged.

The new exact donor-to-stage metadata record reuses the already frozen archive
and witness-selection after-manifest. Its complete inventories and review digest
are pinned; no generic source-hash waiver or new full-fixture archive is added.
The original strict classifier rejections remain external evidence. All old
transition records and approvals remain unchanged, and the new seven-control
owner rejects unreviewed inventory, source, outcome, gas, order, state and
identity changes.

The new Contracts release identity is
`sha256:e3dc23d3e43325fde45a3d07e175ce79e66ead7793ebcc41a1633fb3db978044`;
its fixture package is
`sha256:9323cd0b2b4202c08d8165a99102aa6d8a52f3e718fc33647e34ba211859a60f`.
Generated references, fresh clean/native quality gates, new immutable artifacts
and downstream qualification remain separate work. Static validation does not
claim Java-template `PACKAGE_VALID` or implementation conformance. Existing
e101 API-documentation, clean-build and artifact receipts are not fresh evidence.
Exact commands, complete manifests, original failures and approvals are retained
in the root-owned evidence runs, including `language-c03-deferred-affected-01`,
`language-c03-deferred-regeneration-01`, `language-c03-deferred-stage-review-01`
and `language-c03-deferred-import-01`.
