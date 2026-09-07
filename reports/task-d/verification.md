# Task D verification evidence

Final result: **326 tests passed, 0 failed** across the three disjoint selected
source sets: **79 core + 244 root compatibility + 3 model**. The explicit
`graph.specialize` assertions are included in the final core rerun. Tests and
assertion matrices are counted by their JUnit test methods; repeated runs and
loop iterations are not counted as additional final tests.

Final Gradle elapsed times: core 5 seconds, compatibility 9 seconds, model/API/
package-cycle/JavaDoc checks 4 seconds. These are warm local execution times,
not performance benchmarks. Per-class JUnit execution times are in
`final-test-counts.txt`.

All commands ran from `/private/tmp/codex-campaign-type-definitions`, with the
checked-in Gradle 9.6.0 wrapper and `JAVA_HOME=$(/usr/libexec/java_home -v 21)`.
No clean/full conformance/release task was run. Outputs were redirected to the
numbered logs listed below; raw diagnostic logs remain locally in
`/private/tmp/codex-task-d-run-evidence/`.

## Final commands

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :blue-language-core:test \
  --tests 'blue.language.runtime.Definition*Test' \
  --tests 'blue.language.runtime.GenderDefinitionLifecycleTest' \
  --tests 'blue.language.conformance.*Scalar*Test' \
  --tests 'blue.language.conformance.ConformanceEngineInlineParentIdentityTest' \
  --tests 'blue.language.merge.CompletedValueValidatorPresenceTest' \
  --tests 'blue.language.merge.CanonicalIdentityProvenanceFailClosedTest' \
  --tests 'blue.language.merge.ListOverlayCanonicalIdentityParityTest' \
  --tests 'blue.language.resolve.MinimizedOverlayCanonicalIdentityParityTest' \
  --tests 'blue.language.identity.CanonicalIdentityInputReconstructorTest' \
  --tests 'blue.language.identity.CanonicalTypeIdentityEvidenceTest' \
  --tests 'blue.language.matching.NodeTypeMatcherLimitedResolutionTest' \
  --tests 'blue.language.matching.FrozenTypeIdentityParityTest' --console=plain

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :test \
  --tests 'blue.language.identity.EnumConstraintMembershipTest' \
  --tests 'blue.language.identity.SchemaEnumCanonicalizerTest' \
  --tests 'blue.language.SchemaVerifierTest' \
  --tests 'blue.language.SchemaVerifierMinLengthTest' \
  --tests 'blue.language.RootSchemaPayloadKindTest' \
  --tests 'blue.language.ResolvedInstanceSchemaValidationTest' \
  --tests 'blue.language.ResolvedSchemaValidationLifecycleTest' \
  --tests 'blue.language.matching.FrozenSchemaMatcherTest' \
  --tests 'blue.language.NodeDeserializerTest' \
  --tests 'blue.language.BlueIdentityAndSpecializationTest' \
  --tests 'blue.language.RootReferenceSnapshotTest' \
  --tests 'blue.language.ExactEmptyObjectSemanticsTest' \
  --tests 'blue.language.EmptyObjectCandidateConformanceTest' --console=plain

JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew \
  :blue-language-core:apiBaselineDiff \
  :blue-language-core:verifyJavaPackageCycles \
  :blue-language-core:javadoc \
  :blue-language-model:test \
  :blue-language-model:apiBaselineDiff \
  :blue-language-model:verifyJavaPackageCycles \
  :blue-language-model:javadoc --console=plain
```

The owning module API/package-cycle/JavaDoc tasks all passed. Core API inventory
reports **9 additive entries and no removals**: two utility classes and their
four methods, the definition default SPI hook/forwarder, and
`BlueResolution.resolveDefinition`. The model inventory reports two additions
already present at the starting commit (`Nodes.isBareFieldlessBuilder` and
`Nodes.isSchemaEnumValue`), with no new model public signatures in this change.
No API baseline or migration ledger was recaptured. The exact inventory deltas
are included as `core-api-diff.json` and `model-api-diff.json`.

## Iteration record

Every test invocation used `--console=plain` and the Java-home prefix above.
The final command blocks define the exact named selector sets; earlier runs
used the indicated subsets. This table records failures instead of hiding
exploratory or test-authoring errors.

| Log | Task / selectors | Tests / failed | Gradle time / result |
|---|---|---:|---|
| 01-baseline | core: DefinitionValidationTest | 2 / 2 | 11s, reproduced |
| 02-scalar-baseline | core: InheritedScalarConformanceTest | 3 / 1 | 2s, reproduced |
| 03-first-fix | core: DefinitionValidationTest, InheritedScalarConformanceTest; mistakenly included nonexistent merge.BasicScalarPayloadKindTest | 5 / 0 | 3s, pass; omitted scalar class corrected later |
| 04-enum-scalar | mixed `:blue-language-core:test` and unqualified `test` with enum filter | no tests executed | 1s, selector scoped to wrong task; corrected to separate explicit tasks |
| 05-definition-matrix | core: runtime.Definition*Test, conformance.*Scalar*Test | 16 / 3 | 3s, provider classification, empty enum, YAML yes-as-Boolean test typo |
| 06-enum | root: EnumConstraintMembershipTest | 12 / 4 | 9s, enum metadata probes/completion |
| 07-lifecycle | core definition/scalar/Gender plus CompletedValueValidatorPresenceTest | 27 / 3 | 5s, fixed empty-object provenance, resolved-vs-source API probe, custom type retention |
| 08-enum | root: EnumConstraintMembershipTest | 14 / 6 | 5s, metadata probe required declared List |
| 09-lifecycle | preceding core set plus unmatched RootReferenceSnapshotTest filter | 28 / 3 | 6s, representation API probes and numeric applicability |
| 10-enum | root: EnumConstraintMembershipTest | 15 / 1 | 4s, custom typed enum parser shape |
| 11-definitions | core definition/scalar/Gender/presence including reference/cache tests | 32 / 2 | 5s, large Integer schema wire form and inherited canonical evidence |
| 12-adjacent-schema | first eight root selectors in final compatibility command | 141 / 0 | 8s, pass |
| 13-api-package | core/model API diff and package-cycle tasks | no tests | 1s, pass |
| 14-core-verification | final core selector set before numeric matching method added | 78 / 0 | 5s, pass |
| 15-compatibility-verification | final compatibility selector set before enum budget method added | 243 / 0 | 7s, pass |
| 16-numeric-matching | core: BasicScalarPayloadKindTest | no tests executed | 546ms, in-flight helper refactor missing imports; corrected |
| 17-final-core | final core selector set | 79 / 0 | 5s, pass |
| 18-final-compatibility | final compatibility selector set including enum budget/property tests | 244 / 0 | 9s, pass |
| 19-final-specialization | final core selector set including graph.specialize assertions | 79 / 0 | 5s, pass |
| 20-final-module-checks | final owning-module command above | 3 / 0 | 4s, pass |

The first Gradle attempt before log 01 failed to open its existing cache lock
under sandbox permissions; it ran no tests. It was retried with authorized
cache access. Successful compilation emitted Java 8 target-obsolescence warnings
and existing Gradle deprecation notices; no gate was weakened to suppress them.

## Non-build checks and evidence limits

- `git rev-parse --show-toplevel`, `git rev-parse HEAD`,
  `git symbolic-ref --short HEAD`, `git status --porcelain`, `git worktree list`.
- Repository/ancestor instruction discovery with `rg --files` and direct reads.
- `git diff --check`: passed.
- `cmp` primary specification / mirror: passed, byte-identical.
- `git diff --cached --unified=0 -- <primary> <mirror>`: preserved in `specification.patch`.
- `shasum -a 256`: base spec
  `0dc2942bfbabbe994debb1038fe4d1c7a4ddefa9b25bc26cb0f68a7380cfbd06`;
  candidate spec
  `c9466da878d02bc97a77b227f7341b96a739803dcb56245d0a15d10aa5ff6c17`.
- Read-only independent review completed with no remaining identified blocker.

The exact previous digest occurrences are listed in
`stale-spec-digest-occurrences.txt`; the list includes historical evidence that
must remain historical, as well as authoritative bindings that integration
must update. In particular, the Source preprocessing environment, Contracts
closure release manifest, and combined package manifest still bind the prior
specification. Their content-addressed dependency chains and dependent fixtures
were deliberately not regenerated on this candidate branch.

No full Language/Contracts conformance, broad identity/gas baseline, release,
benchmark, A/B generation, Mini endpoint, or MyOS integration success is claimed.
