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

## Pending binding and verification work

This source edit does not manually rewrite generated fixture expectations.
The maintained cyclic-only bootstrap has restored the two implementation roles
to their pinned-Core values, with matching Contracts API constants. The existing
independent lifecycle helper changes only its two reviewed role constants;
its original role pair and all frozen event/work/gas/rejection constructors and
assertions remain intact. The bootstrap receipt is external to this source.
The 742-path implementation inventory and 817-source ownership count remain
unchanged: no production file was added or deleted. Four implementation file
digests change, so the generated release/source manifests, aggregate/resource
bindings and exact transition review must still be refreshed against the new
source after the cyclic bootstrap. The Language specification
and source-preprocessing specification binding remain at pinned upstream;
the approved Contracts retarget amendment is retained.

Next, after source review: verify the native API/architecture and affected Core,
minimization, selected-scope and Contracts owners; use maintained binding and
package generation; inspect every staged semantic/identity difference before
import; refresh generated API documentation and qualify a newly sealed tuple.
Historical transition approvals must remain intact. Existing e101 fixture,
API-documentation, clean-build and artifact receipts are not fresh evidence.

No JVM test, package regeneration or artifact export is claimed by this source
record. The local input commit and subsequent results belong in the root-owned
evidence receipts.
