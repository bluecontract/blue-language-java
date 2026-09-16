# Current working-occurrence Source contributions

Status: isolated additive capability, not yet qualified. This is required by
the independently reproduced Coordination PR #18 named/exact-selector
normalization divergence, not by the unrelated Language PR #34.

## Problem and example

An exact provider definition explicitly contains `constants: {type: Dictionary}`.
Direct Compute selection correctly rejects this as a BEX plain-name container;
named selection reached the same definition's effective value and incorrectly
removed the declaration because it equalled an inherited default. Looking only
at resolved content cannot distinguish absence from an exact contribution.

The current `canonicalAt`/Source-index reads cannot follow pure references or
type-provided descendants. The selected executable-body capability cannot open
arbitrary siblings merely because a definition name points there. Runtime-state
reads also omit earlier patches in the current WorkingDocument preview.

## Contract

`WorkingDocument.sourceContributionsAt(String)` returns an immutable list of
exact contributions to one current occurrence, containing type ancestors before
local overlays. It is not a merged node, reconstructed original authored text,
or a value with a newly calculated Source identity. The selected occurrence's
own type defaults are not direct contributions.

Only reference/type ancestors on the requested path are opened, through the
existing invocation-bound verified manager. Managed content uses matching
path/BlueId evidence. The list tracks Source overlay positions, replacements,
appends, and canonical final list payloads at their effective indices. Working
patches are visible, earlier returned immutable values stay unchanged, and reads
after the preview closes fail. No provider or mutable runtime is exposed.

Existing resolution validates the working document and list controls. This read
selects provenance; it does not replace conformance, resolve unrelated content,
publish state, or define a second source-identity algorithm.

## Focused verification

Integrated source `c17c5e374003e2d5854683429c487434f658ec15` passes all 36
Contracts cases in `upstream-review-language-integrated-03` (fresh execution,
zero failures/errors/skips). Archive SHA-256:
`fce915f39a8efacf159913aea5fe8d4ec73ad0ae9e6098994d696e8a2c08acff`.
The same run passes 40 selected root controls; the 20 core controls remain
UP-TO-DATE from their previous pass. This qualifies the focused capability,
not its Coordination consumer or the full release. Failure histories below
remain recorded rather than replacing them with this later result.

`ReferenceTransparentExecutionTest` adds seven controls for expanded exact refs,
partial inherited containers, current preview patches, inherited/replaced/appended
list positions, nested item-type contributions, dictionary value-type validation
versus explicit member inheritance, and path-bound managed reads. Existing missing/invalid-reference,
cyclic, patching and cold-sibling tests remain enabled. Coordination keeps exact
selector controls and adds referenced/inherited/partial-overlay SDK cases.

Qualification must use the same staged local Language/Contracts artifact as the
Coordination follow-up; published RC25 does not expose this new method. No pass
claim is made before that grouped run.

## First integrated diagnostic: fixture corrections

The first 36-case Contracts run passed 32 cases. Four new controls did not
reach the new API: two attempted direct hashing with inline type/valueType
positions, one omitted the mandatory List type for itemType, and one combined
a referenced inherited library with a local overlay whose existing resolved
read returned no child. These results do not demonstrate a production defect
in the new capability.

The corrected controls retain separately hashed pure type references, declare
List/Dictionary explicitly, and keep the partial inherited library inline
inside its referenced root type. That last control still checks a partial
local container overlay plus an inherited exact definition reference; the
separate already-expanded/reference-parent controls retain reference traversal
coverage. No expected values were changed, and no production code was changed
for these fixture corrections. The corrected batch remains unverified.

The next run passed 35/36 Contracts controls. Its remaining pre-API failure
disproved the earlier review assumption that Dictionary valueType injects its
fields into an untyped value. `DictionaryProcessor.java:69` and `:217` only
validate member compatibility; object merging does not apply valueType as an
implicit type. The new provenance traversal's speculative valueType injection
was therefore removed. List itemType inheritance remains, matching actual list
resolution. The corrected control asserts no definition for untyped `alice:{}`
in both resolved and provenance reads, then asserts the exact definition when
Alice explicitly declares that type. It remains one control, so the Contracts
class still contains 36 tests. This is a correction to the proposed provenance
API and its test assumption, not a specification or dictionary-rule change.
