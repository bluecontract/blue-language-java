# Nested-value Source identity upstream-review diagnostic

> Historical donor/C03 evidence only. The current baseline defers the whole C03
> family; see [the approved deferral boundary](baseline-c03-deferral.md). This
> record preserves the diagnostic, exact-capture oracle and test-style history;
> it is not fresh qualification of the C03-deferred baseline.

## Qualification records and test-style correction

Qualification is recorded in immutable external receipts after each source
freeze; a final receipt must not require a self-referential documentation
commit. The following is an audited checkpoint, not approval of a later source.

Language `e6cb7c3fe0de5bc9f3b5a48147f01144bb9eb1fa` completed the unfiltered
clean build in `language-successor-full.deKRKV/first`: **3,798 fresh cases,
3,797 passes, one failure, zero errors/skips**. The only failure is
`SourceStyleConventionsTest.shouldKeepTestAssertionsInsideThenSections`.
It names
`SelectedScopeContentBlueIdFailFirstTest.shouldVerifySelectedChildUsesItsExactDirectIdentityInsteadOfEmptyNodeIdentity`,
whose two new pre-state assertions appeared before `// then`.
All semantic test methods passed; the build itself remains failed.
Archive SHA-256:
`5f80cdfd3a16e8b562d65293da83c83518b0297bd9fdc74b0f569c351ca30dd7`.

The correction is test layout only: capture the original child type and its
initial canonical contribution before initialization, then assert those captured
values in Then. Keep both independent checks and every existing exact child/root
hash, initialization marker, inline/reference and direct-identity assertion.
Do not replace the pre-state observation with a post-initialization lookup.
No production, API, identity or minimization rule changes.

On the same sealed library tuple (Coordination `54a5709`), the completed
focused MyOS checkpoint `5ab93cd` passes 189 cases: 184 host, two typed-identity
and three named-Compute cases. The three previously disabled upstream
reproductions actually execute and pass; this is not full MyOS acceptance.
The grouped staged bridge also passes 40 Contracts controls and all 110
Coordination consumers/selection cases. Its post-Gradle Ruby harness failure
is preserved separately from the verified successful test execution.

A final source still needs the maintained clean-build and quality gates.
Native incremental reuse may report unchanged task results as retained, but
cannot be presented as a new clean-build receipt. Later acceptance status stays
in the source/artifact-bound external handoff rather than rewriting this
historical checkpoint after every run.

## Earlier integrated verification (historical)

The latest isolated selector successor,
`3474e82bb27f45716c5f8331bf1aab759be5f9ca`, freshly passed **all 40 Contracts
reference-transparent execution controls** (zero failures, errors or skips)
in `upstream-review-list-slot-02`. It corrects the new Source-contribution
lookup after a valid positional replacement; the existing resolver already
retained the inherited slot type. No core minimizer or protocol rule changed.
The [focused reproduction and correction](source-list-contribution-review.md)
records the preceding red result and exact proof. Green archive SHA-256:
`64fca7779e52d8b8c8f2745f8cccdebe19b359923ac7251c231ca307feadf990`.
The final integrated source/artifact tuple still requires qualification;
this focused result does not rerun the core and root groups listed below.

On integrated runtime source `c17c5e374003e2d5854683429c487434f658ec15`,
`upstream-review-language-integrated-03` freshly executed and passed all 40
selected root identity/lifecycle controls and all 36 selected Contracts controls.
The 20 core identity/minimization controls were UP-TO-DATE from their preceding
passing run; this is not 96 newly executed tests. Complete focused archive SHA-256:
`fce915f39a8efacf159913aea5fe8d4ec73ad0ae9e6098994d696e8a2c08acff`.
The maintained module API check passes. Generated references and the exact
additive API ledger are refreshed separately without changing package identities.
Full library and packaged MyOS acceptance remain required. Historical unverified
statements below describe the earlier candidates, not this focused result.

## Original isolated review

Isolated review candidate, not integrated baseline acceptance. Branch
`codex/diagnose-nested-value-identity-review` starts at Language PR #33 head
`036540104715e0841392a6f5055232a95b134802`, descending from its remote `next`
base `4128fb190b9f92fa6aa094ebba11803f312cd9fd` (RC24). The original red probe
changed tests only; its successor includes the minimal reconstruction correction
below. Dependency configuration is unchanged. PR #34 is not included or required.

Parent-owned filter:
`./gradlew :blue-language-core:test --tests blue.language.runtime.NestedValueReferenceIdentityTest
--continue --max-workers=1`. Use the branch's own modules/dependency locks;
Coordination and MyOS are not needed to establish this Language invariant.

[Unresolved review](https://github.com/bluecontract/blue-language-java/pull/33#discussion_r3976482299):
the patch retains an inherited custom type for inline children, while the final
pure-reference replacement retains a stored child's original untyped BlueId.

The added test stores an untyped `{title: {type: Text, value: Coffee}}` child and
places it below `Holder.terms`, whose type is custom `Terms`. It first proves the
stored child has no standalone custom type and verifies inline/reference direct
content and expansion equality. It then requires equal containing Source IDs
and cold minimized-reload identity. Expected equality comes from verified
reference/materialization transparency (Language §§1, 3.2), not a golden hash
derived from the implementation under test. Failure diagnostics include the
canonical inputs and retained child identity.

The same existing class retains controls for explicitly typed children under
typed/unconstrained parents, custom scalar shorthand, and an untyped child under
Dictionary. Do not weaken those controls to fix only the new case.

PR #33 changes one production reconstruction file and two test files. Those
paths are unchanged in our frozen `ef3ebd416cd11f627cda8169c6902218b3a8363d`
relative to this PR's base, but low textual overlap is not semantic approval:
Source IDs can change globally. Qualify this reproduction and the owner's tests
before integrating the preserved upstream commit plus any separately justified
follow-up. Our witness-selection fixes concern other paths and remain intact.

## Reproduced difference and narrow correction

`upstream-review-nested-01` executed all five cases: four passed; the added
untyped-child/custom-parent case failed only containing Source identity. Direct
identity, verified expansion and minimized cold reload controls passed. The
archived report shows the inline canonical child acquiring `type: Terms`, while
the exact stored untyped reference necessarily retained its original identity.

**Correction:** when the object's type is contributed only by its enclosing
field (`authoredType == null`) and equals that inherited constraint, omit it from
the child's canonical contribution. Preserve the upstream fix for an explicitly
typed standalone child, and its separate proven custom-scalar refinement case.
No provider value, pure reference, or stored BlueId is rewritten.

**Rationale:** reference/materialization transparency and the Source rules for
preserving references without an instance overlay require equal identity here;
fully derivable inherited content is not a new standalone authored contribution
(Language §§3.2, 13.4, 13.5.1). Injecting the enclosing type into an exact untyped
reference is not an alternative fix: that would replace the referenced value.
This is an identity-conformance correction, not an attachment or processing
policy change. The successor still requires tests and broader qualification.

## Follow-up: preserve the Source lane during minimization

The frozen `6a23e50` probe (`upstream-review-nested-02`) again executed five
cases: the untyped-child correction passed, but the previously passing
explicitly typed child under a typed parent failed **only its cold minimized
reload** (line 63 of `NestedValueReferenceIdentityTest`). Its inline/reference
Source identities agreed before minimization. This is a real regression, not
permission to relax that assertion.

Small example:

```yaml
# Holder's type already declares terms: {type: Terms}.
type: Holder
terms:
  type: Terms          # Explicit standalone custom-type contribution.
  title: Coffee
```

The old minimizer received only the resolved value and type-identity evidence.
It removed the explicit `Terms` type as equal to the field constraint. Reloading
that output therefore followed the untyped-child identity rule. By contrast,
an originally untyped child must remain untyped; inserting `Terms` into both
forms would reintroduce the first defect. Language §13.3 requires a minimized
Source to preserve both complete meaning and Source-derived BlueId.

**Correction:** pass the already-preprocessed Source from
`BlueLanguageRuntime.minimize` into the existing minimizer traversal. When the
normal type diff would erase an explicitly authored custom type, retain that
exact declaration. Do not assign a custom type to untyped children or primitive
shorthand. Carry the matching Source occurrence through properties, contracts,
and list items; preserve exact Source references. Fully derivable ordinary
fields still disappear. Whole equal non-list fields keep the existing canonical
omission rule, with explicit equal/empty-object and list-item controls.

List correspondence uses logical item positions, not array offsets in the
Source: `$previous` is not an item, `$pos` targets a resolved position,
`$replace` contributes its payload, and appended items occupy the resolved
suffix. One per-list map establishes that correspondence in linear time.

**API impact:** add the low-level overload
`MinimizedOverlayBuilder.build(FrozenNode, Node, CanonicalTypeIdentityLookup)`;
the `Node` is the exact preprocessed Source from that same resolution. The
existing two-argument resolved-only overload and its behavior remain unchanged.
`BlueResolution.minimize(Node)` requires no public signature change. The core
public API baseline records the additive method. This does not change Contracts,
BEX, gas, admission, or document-processing boundaries.

**Cost:** no additional resolution, provider read, or whole-document identity
calculation is introduced. Existing resolved traversal and comparisons remain;
Source lookups add linear list-index construction and constant-time child
lookups. Type references cost one small node to retain. An explicitly inline
custom type may retain its original type declaration at that occurrence;
unrelated inherited payload still minimizes. No runtime performance claim is
made before measurement.

**Rejected alternative:** calculate Source identity for input and candidate,
then return the whole original Source if they differ. Returning a correct Source
is legal, but that design can add two full identity pipelines and silently lose
compression for a large document. It also conceals which occurrence lost
provenance. Passing the existing Source lane corrects the cause directly.

### Verification boundary

The first grouped probe, `upstream-review-minimization-01`, ran against frozen
`64ff1b56874f9f0069c17c0ff5d3788160ab3f7d`: **47 of 48 tests passed**, with one
failure, zero errors, and zero skips. All five original identity invocations,
all three canonical-evidence controls, and all 29 root-level minimization/list
controls passed. The sole failure was the new `replace` fixture: it attempted
to replace inherited Text (`obsolete`) with a Holder object, which the existing
payload-kind rule rejected while calculating the **input** Source identity,
before minimization ran. The archived build is
`upstream-review-minimization-01-build.tar.gz`, SHA-256
`04172f7ef21163933d888f8d5a0d3a0c4c6fa9e7c4b87333d69be42475c3a59c`.

The corrected positive fixture replaces an object containing an `obsolete`
field with the typed Holder object. This is a compatible object-to-object
replacement, following the existing `ListControlFormsTest` control. It checks
that the obsolete field disappears and the minimized output still contains
`$pos: 1` plus `$replace`; none of its identity, meaning, or immutability checks
is weakened. A separate negative test preserves rejection of Text-to-Holder
replacement. This is a test-fixture correction, not a production or protocol
change. These two successor cases still require execution.

Keep the five original
`NestedValueReferenceIdentityTest` invocations unchanged. The new
`SourceAwareMinimizedOverlayTest` adds cold-reload controls for nested fields,
contracts, ordinary lists, prefix-plus-append, positional update, whole-item
replacement, exact references, and explicit empty/equal-valued fields/items.
Every case checks Source identity, resolved meaning, and caller-input
immutability; a nested case additionally requires removal of a derivable
payload, ruling out a whole-Source fallback.

The parent's single-lane focused run should include those two core classes and
`blue.language.resolve.MinimizedOverlayCanonicalIdentityParityTest`. Existing
root-level controls remain relevant for final qualification:
`MinimizedOverlayNestedTypedNodeTest`, `MinimizedOverlayPureReferenceProvenanceTest`,
`MinimizedOverlayInlineTypeTest`, and `TutorialListPublicBoundaryTest`. Do not
count unexecuted successor cases, or the independently disabled MyOS
reproduction, as passing on this candidate.

## Integrated lifecycle-oracle reconciliation

The pre-API-group probe on integrated Language
`e37ef0138e57c99427c5626b35aec60cf20ae1a9` executed **96 cases with five
failures**. All 20 core identity/minimization cases and all 29 root-level
minimization controls passed. One failure was the root lifecycle expectation
in `SelectedScopeContentBlueIdFailFirstTest`; the other four were separate
Contracts test-setup failures, not evidence against this minimizer correction.
The build archive SHA-256 is
`1476d35bee62f35e91688ed0fcc81cca6d9d30335c0e2996fcf72762f79c64e0`.

In that fixture the child is authored without `type`; only its enclosing Root
type supplies the child constraint. Its initial fixed fields are wholly
derivable, so the parent's canonical snapshot omits `/child`. PR #33's expected
root builder synthesized `type: ChildType` when recreating that omitted child
to add its lifecycle mutation and initialization marker. That is a different,
explicitly typed Source contribution, not the state the protocol operation
created. The child-capture assertion itself already passed.

The test-only correction retains an actually authored child type if present,
but does not invent one from the enclosing constraint. Explicit pre-execution
assertions establish both the absent authored type and absent initial canonical
child. The expected root still comes from manually constructing the prescribed
child effects and directly hashing their exact canonical representation,
independently of the processor's observed lifecycle result. Root and child
lifecycle/marker assertions and inline-versus-reference captured-content hash
equality remain in place. Existing contextual/typed controls remain enabled.
No production or protocol rule is changed; the successor group must still run.
